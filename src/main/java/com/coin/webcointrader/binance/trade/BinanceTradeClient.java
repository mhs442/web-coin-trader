package com.coin.webcointrader.binance.trade;

import com.coin.webcointrader.binance.common.BinanceApiException;
import com.coin.webcointrader.binance.market.BinanceMarketClient;
import com.coin.webcointrader.binance.stream.BinanceExecutionWaiter;
import com.coin.webcointrader.binance.stream.BinancePrivateWebSocketClient;
import com.coin.webcointrader.binance.stream.dto.BinanceFilledOrderDTO;
import com.coin.webcointrader.binance.stream.dto.BinanceOrderTradeUpdateDTO;
import com.coin.webcointrader.binance.trade.dto.BinanceAlgoOrderHistoryResponse;
import com.coin.webcointrader.binance.trade.dto.BinanceAlgoOrderRequest;
import com.coin.webcointrader.binance.trade.dto.BinanceIncomeResponse;
import com.coin.webcointrader.binance.trade.dto.BinanceOrderRequest;
import com.coin.webcointrader.binance.trade.dto.BinanceOrderResponse;
import com.coin.webcointrader.binance.trade.dto.BinancePositionRiskResponse;
import com.coin.webcointrader.binance.trade.dto.BinanceUserTradeResponse;
import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.enums.OrderCancelResult;
import com.coin.webcointrader.common.enums.LogMessage;
import com.coin.webcointrader.common.enums.OrderSide;
import com.coin.webcointrader.common.enums.PositionCloseType;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.login.dto.ExchangeKeyDTO;
import com.coin.webcointrader.common.login.service.ExchangeKeyService;
import com.coin.webcointrader.common.market.dto.TickerDTO;
import com.coin.webcointrader.common.trade.client.AbstractExchangeTradeClient;
import com.coin.webcointrader.common.trade.dto.ClosedPnlDTO;
import com.coin.webcointrader.common.trade.dto.ClosedPnlQueryDTO;
import com.coin.webcointrader.common.trade.dto.ExecutionDTO;
import com.coin.webcointrader.common.trade.dto.OrderLookupDTO;
import com.coin.webcointrader.common.trade.dto.OrderRequestDTO;
import com.coin.webcointrader.common.trade.dto.OrderResultDTO;
import com.coin.webcointrader.common.trade.dto.PositionCloseDTO;
import com.coin.webcointrader.common.util.AesEncryptor;
import com.coin.webcointrader.common.util.UserApiKeyContext;
import feign.FeignException;
import feign.RetryableException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Binance 거래 클라이언트.
 * 공통 주문 요청을 Binance USDⓈ-M 선물 REST(단방향 모드)로 변환하고, 응답을 공통 DTO로 변환한다.
 * TP/SL은 진입 후 별도 조건부(algo) 주문으로 걸고, 청산 손익은 체결·펀딩 내역으로 직접 계산한다.
 * 체결은 user data stream(ORDER_TRADE_UPDATE)으로 받고 없으면 REST 조회, 외부 청산은 스트림 + 재조정(findLastClose)으로 감지한다.
 */
@Slf4j
@Component
public class BinanceTradeClient extends AbstractExchangeTradeClient {

    // 기본 등급 taker 수수료율 (0.05%, BNB 할인 미적용)
    private static final BigDecimal TAKER_FEE_RATE = new BigDecimal("0.0005");
    // 기본 등급 maker 수수료율 (0.02%, BNB 할인 미적용)
    private static final BigDecimal MAKER_FEE_RATE = new BigDecimal("0.0002");
    // 체결·손익 내역 조회 건수 (Binance 최대값)
    private static final int QUERY_LIMIT = 1000;
    // userTrades startTime 조회 가능 최대 범위 (7일)
    private static final long USER_TRADES_MAX_RANGE_MS = 7L * 24 * 60 * 60 * 1000L;
    // 결과 불명확 에러 코드 (-1000 알 수 없는 오류, -1006 예상 밖 응답, -1007 백엔드 응답 시간 초과)
    private static final Set<Integer> AMBIGUOUS_CODES = Set.of(-1000, -1006, -1007);
    // 주문 없음
    private static final int CODE_ORDER_NOT_FOUND = -2013;
    // 취소할 주문 없음 (이미 전부 체결·취소)
    private static final int CODE_CANCEL_UNKNOWN_ORDER = -2011;
    // 이미 같은 마진 모드 (정상 처리)
    private static final int CODE_MARGIN_NOT_MODIFIED = -4046;
    // 수수료 환산용 BNB 시세 심볼
    private static final String BNB_PRICE_SYMBOL = "BNBUSDT";
    // 체결 이벤트 실행 유형 (TRADE 일반 체결, CALCULATED 강제청산·ADL 체결)
    private static final Set<String> CLOSE_EXEC_TYPES = Set.of("TRADE", "CALCULATED");
    // 전량 체결 상태
    private static final String FILLED = "FILLED";
    // 거래소 개입 청산 clientOrderId 접두어 (강제청산, ADL, 상장폐지 정산)
    private static final List<String> EXCHANGE_CLOSE_PREFIXES = List.of("autoclose-", "adl_autoclose", "settlement_autoclose-");

    private final BinanceTradeFeignClient feignClient;
    private final BinanceMarketClient marketClient;
    private final BinanceExecutionWaiter executionWaiter;
    private final BinancePrivateWebSocketClient privateWebSocketClient;
    // 청산 방식 판정(REST 호출 가능)·청산 이벤트 발행 전용 스레드 — WebSocket 수신을 막지 않고, 단일 스레드로 수신 순서 유지
    private final ExecutorService closeEventExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "binance-close-event");
        t.setDaemon(true);
        return t;
    });

    public BinanceTradeClient(BinanceTradeFeignClient feignClient, BinanceMarketClient marketClient,
                              ExchangeKeyService exchangeKeyService, AesEncryptor aesEncryptor, Clock clock,
                              BinanceExecutionWaiter executionWaiter,
                              BinancePrivateWebSocketClient privateWebSocketClient) {
        super(exchangeKeyService, aesEncryptor, clock);
        this.feignClient = feignClient;
        this.marketClient = marketClient;
        this.executionWaiter = executionWaiter;
        this.privateWebSocketClient = privateWebSocketClient;
    }

    /**
     * 빈 초기화 시 개인 스트림에 주문 업데이트 콜백을 등록한다. (청산 체결 → 포지션 청산 리스너 팬아웃)
     */
    @PostConstruct
    public void init() {
        privateWebSocketClient.addOrderUpdateCallback(this::onOrderUpdate);
    }

    /**
     * 종료 시 청산 이벤트 스레드를 정리한다.
     */
    @PreDestroy
    public void destroy() {
        closeEventExecutor.shutdownNow();
    }

    @Override
    public ExchangeType getExchangeType() {
        return ExchangeType.BINANCE;
    }

    @Override
    public BigDecimal getTakerFeeRate() {
        return TAKER_FEE_RATE;
    }

    @Override
    public BigDecimal getMakerFeeRate() {
        return MAKER_FEE_RATE;
    }

    // ─── API 키 바인딩 ───

    @Override
    protected void bindApiKey(String apiKey, String apiSecret) {
        UserApiKeyContext.set(apiKey, apiSecret);
    }

    @Override
    protected void unbindApiKey() {
        UserApiKeyContext.clear();
    }

    // ─── 주문 ───

    /**
     * 공통 주문을 Binance 시장가/지정가 주문으로 전송한다. (POST /fapi/v1/order)
     * price가 있으면 지정가(LIMIT, GTC), 없으면 시장가로 보낸다.
     * positionSide 는 보내지 않는다. (단방향 모드 전제 — 헤지 모드 계정은 거래소가 거부)
     * [사용자 정책 2026-10-27] 진입 전·시장가 청산 접수 후 심볼의 조건부 주문을 전부 취소한다. (사용자가 직접 건 조건부 주문 포함)
     * [사용자 정책 2026-10-28] 익절 지정가 접수 후에는 포지션이 남아 있으므로 조건부 주문(안전망)을 취소하지 않는다.
     * [사용자 정책 2026-10-27] 진입 후 TP/SL 조건부 주문 접수가 실패해도 진입은 성공으로 처리한다. (경고 로그만)
     *
     * @param request 주문 요청 (clientOrderId → newClientOrderId)
     * @return 주문 ID와 clientOrderId
     * @throws CustomException 거래소가 거부를 확정한 경우 (ORDER_FAILED, "[code] msg" 형태)
     * @throws RuntimeException 결과 불명확 오류는 그대로 전파 (공통 처리에서 clientOrderId 조회로 복구)
     */
    @Override
    protected OrderResultDTO submitOrder(OrderRequestDTO request) {
        String symbol = request.getSymbol();
        // 진입 전: 이전 포지션의 남은 조건부 주문이 새 포지션을 닫지 않도록 정리
        if (!request.isReduceOnly()) {
            cancelAlgoOrders(symbol);
        }

        BinanceOrderResponse response;
        try {
            response = feignClient.newOrder(BinanceOrderRequest.builder()
                    .symbol(symbol)
                    .side(request.getSide().name())
                    .type(request.getPrice() != null ? "LIMIT" : "MARKET")
                    .quantity(request.getQty())
                    // 지정가만 가격 + GTC 지정 (시장가는 필드 생략)
                    .price(request.getPrice())
                    .timeInForce(request.getPrice() != null ? "GTC" : null)
                    // 청산 주문만 reduceOnly 지정 (진입 주문은 필드 생략)
                    .reduceOnly(request.isReduceOnly() ? "true" : null)
                    .newClientOrderId(request.getClientOrderId())
                    .build());
        } catch (BinanceApiException e) {
            // 결과 불명확 → 원 예외 전파 (공통 처리에서 복구 시도)
            if (isAmbiguousFailure(e)) throw e;
            // 거부 확정 (원인 추적을 위해 "[code] msg" 형태로 기록)
            throw new CustomException(ExceptionMessage.ORDER_FAILED, e.getMessage());
        }
        if (response == null) {
            throw new CustomException(ExceptionMessage.ORDER_FAILED, "응답 없음");
        }

        if (request.isReduceOnly()) {
            // 시장가 청산 접수 후: 포지션이 사라져 쓸모없어진 조건부 주문 정리
            // 지정가 익절은 접수만 된 상태(포지션 유지) → 안전망 조건부 주문을 남겨둠
            if (request.getPrice() == null) {
                cancelAlgoOrders(symbol);
            }
        } else {
            placeProtectionOrders(request);
        }
        return OrderResultDTO.builder()
                .orderId(response.getOrderId())
                .clientOrderId(request.getClientOrderId())
                .build();
    }

    /**
     * 심볼의 미체결 조건부 주문을 전부 취소한다. (DELETE /fapi/v1/algoOpenOrders)
     * 실패해도 주문 흐름을 막지 않는다.
     *
     * @param symbol 종목 심볼
     */
    private void cancelAlgoOrders(String symbol) {
        try {
            feignClient.cancelAllAlgoOpenOrders(symbol);
        } catch (Exception e) {
            log.warn("[BINANCE 조건부 주문 취소 실패] symbol={}: {}", symbol, e.getMessage());
        }
    }

    /**
     * 진입 주문의 TP/SL을 포지션 전체 청산 조건부 주문으로 건다. (POST /fapi/v1/algoOrder)
     * 트리거 기준은 최종 체결가(CONTRACT_PRICE), clientAlgoId 는 "{clientOrderId}-TP/-SL".
     * 각 주문은 독립적으로 시도하며 실패는 경고 로그만 남긴다.
     *
     * @param request 진입 주문 요청 (takeProfit/stopLoss null이면 해당 주문 생략)
     */
    private void placeProtectionOrders(OrderRequestDTO request) {
        // 보호 주문은 진입 반대 방향
        String closeSide = request.getSide() == OrderSide.BUY ? OrderSide.SELL.name() : OrderSide.BUY.name();
        if (request.getTakeProfit() != null) {
            placeAlgoOrder(request, closeSide, "TAKE_PROFIT_MARKET", request.getTakeProfit(), "-TP");
        }
        if (request.getStopLoss() != null) {
            placeAlgoOrder(request, closeSide, "STOP_MARKET", request.getStopLoss(), "-SL");
        }
    }

    /**
     * 조건부 주문 1건을 접수한다. 실패는 경고 로그만 남긴다.
     *
     * @param request      진입 주문 요청
     * @param side         주문 방향 (진입 반대)
     * @param type         TAKE_PROFIT_MARKET / STOP_MARKET
     * @param triggerPrice 트리거 가격
     * @param idSuffix     clientAlgoId 접미사 (-TP / -SL)
     */
    private void placeAlgoOrder(OrderRequestDTO request, String side, String type, String triggerPrice, String idSuffix) {
        // clientOrderId 없으면 거래소 자동 생성
        String clientAlgoId = request.getClientOrderId() != null ? request.getClientOrderId() + idSuffix : null;
        try {
            feignClient.newAlgoOrder(BinanceAlgoOrderRequest.builder()
                    .algoType("CONDITIONAL")
                    .symbol(request.getSymbol())
                    .side(side)
                    .type(type)
                    .triggerPrice(triggerPrice)
                    .closePosition("true")
                    .workingType("CONTRACT_PRICE")
                    .clientAlgoId(clientAlgoId)
                    .build());
        } catch (Exception e) {
            log.warn("[BINANCE 조건부 주문 실패] symbol={}, type={}, triggerPrice={}: {} — 진입은 유지",
                    request.getSymbol(), type, triggerPrice, e.getMessage());
        }
    }

    /**
     * 읽기 타임아웃·연결 끊김(RetryableException), 5xx, 결과 불명 코드(-1000/-1006/-1007)는
     * 거래소가 주문을 이미 접수했을 수 있다. 그 외 Binance 에러 코드는 거부 확정으로 본다.
     */
    @Override
    protected boolean isAmbiguousFailure(RuntimeException e) {
        if (e instanceof BinanceApiException be) {
            // 본문 파싱 실패 시 code 에 HTTP 상태가 들어오므로 5xx 도 결과 불명
            return AMBIGUOUS_CODES.contains(be.getCode()) || be.getCode() >= 500;
        }
        return e instanceof FeignException fe && (fe instanceof RetryableException || fe.status() >= 500);
    }

    /**
     * clientOrderId로 주문 상태를 조회한다. (GET /fapi/v1/order)
     * 체결 수량이 있으면 포지션이 변동된 것이고, NEW/PARTIALLY_FILLED는 체결 진행 중이다.
     *
     * @param request 원 주문 요청
     * @return 주문 상태, 주문 없음(-2013)·응답 없음이면 null (재조회 대상)
     */
    @Override
    protected OrderLookupDTO lookupOrder(OrderRequestDTO request) {
        BinanceOrderResponse order;
        try {
            order = feignClient.getOrder(request.getSymbol(), null, request.getClientOrderId());
        } catch (BinanceApiException e) {
            // 아직 반영 전 → 재조회 대상
            if (e.getCode() == CODE_ORDER_NOT_FOUND) return null;
            throw e;
        }
        if (order == null) return null;
        boolean accepted = isPositive(order.getExecutedQty())
                || "NEW".equals(order.getStatus()) || "PARTIALLY_FILLED".equals(order.getStatus());
        return OrderLookupDTO.builder().orderId(order.getOrderId()).accepted(accepted).build();
    }

    /**
     * 주문을 origClientOrderId로 취소한다. (DELETE /fapi/v1/order)
     *
     * @param symbol        종목 심볼
     * @param clientOrderId 취소할 주문의 사용자 지정 ID
     * @return 정상 응답 → CANCELLED, -2011(이미 체결·취소) → NOT_FOUND, 그 외 오류 → FAILED
     */
    @Override
    protected OrderCancelResult doCancelOrder(String symbol, String clientOrderId) {
        try {
            feignClient.cancelOrder(symbol, clientOrderId);
            return OrderCancelResult.CANCELLED;
        } catch (BinanceApiException e) {
            // 이미 전부 체결·취소된 주문
            if (e.getCode() == CODE_CANCEL_UNKNOWN_ORDER) return OrderCancelResult.NOT_FOUND;
            log.warn("[BINANCE 주문 취소] 거부 clientOrderId={}: {}", clientOrderId, e.getMessage());
            return OrderCancelResult.FAILED;
        }
    }

    // ─── 마진 모드 / 레버리지 ───

    /**
     * 심볼 마진 모드를 ISOLATED로 전환한다. (POST /fapi/v1/marginType)
     *
     * @param symbol 종목 심볼 (Binance는 심볼 단위 설정)
     * @throws CustomException -4046(이미 ISOLATED) 외 오류 (SWITCH_MARGIN_MODE_FAILED)
     */
    @Override
    protected void doSwitchToIsolated(String symbol) {
        try {
            feignClient.changeMarginType(symbol, "ISOLATED");
        } catch (BinanceApiException e) {
            // 이미 격리 모드 → 정상
            if (e.getCode() == CODE_MARGIN_NOT_MODIFIED) return;
            throw new CustomException(ExceptionMessage.SWITCH_MARGIN_MODE_FAILED, e.getMessage());
        }
    }

    /**
     * 심볼 레버리지를 설정한다. (POST /fapi/v1/leverage, 같은 값이어도 정상 응답)
     *
     * @param symbol   종목 심볼
     * @param leverage 레버리지 (예: "10")
     * @throws CustomException 설정 실패 시 (SET_LEVERAGE_FAILED)
     */
    @Override
    protected void doSetLeverage(String symbol, String leverage) {
        try {
            feignClient.changeLeverage(symbol, leverage);
        } catch (BinanceApiException e) {
            throw new CustomException(ExceptionMessage.SET_LEVERAGE_FAILED, e.getMessage());
        }
    }

    // ─── 체결 / 손익 조회 ───

    @Override
    public boolean expectExecution(Long userId, String clientOrderId) {
        return executionWaiter.expect(userId, clientOrderId);
    }

    @Override
    public void cancelExecution(String clientOrderId) {
        executionWaiter.cancel(clientOrderId);
    }

    @Override
    protected ExecutionDTO awaitExecution(String clientOrderId, long waitMillis) {
        BinanceFilledOrderDTO filled = executionWaiter.await(clientOrderId, waitMillis);
        // null(미등록·시간 초과·체결 없이 종료) → 호출부가 REST 조회로 fallback
        return filled != null ? toExecutionDTO(filled) : null;
    }

    /**
     * 주문의 체결 내역을 조회한다. (GET /fapi/v1/userTrades, 가중치 5)
     * 수수료는 USDT로 환산하고, 전량 반영 판단용 원 주문 수량은 GET /fapi/v1/order(가중치 1)로 채운다.
     *
     * @param orderId 거래소 주문 ID
     * @param symbol  종목 심볼
     * @return 체결 레코드 목록 (미반영 시 빈 목록)
     */
    @Override
    protected List<ExecutionDTO> fetchExecutions(String orderId, String symbol) {
        List<BinanceUserTradeResponse> trades = feignClient.getUserTrades(symbol, orderId, null, QUERY_LIMIT);
        if (trades == null || trades.isEmpty()) return List.of();
        String orderQty = fetchOrderQty(symbol, orderId);
        return trades.stream().map(t -> toExecutionDTO(t, orderQty)).toList();
    }

    /**
     * 원 주문 수량을 조회한다. 실패해도 체결 집계는 진행한다. (null → 반영 완료 간주)
     *
     * @param symbol  종목 심볼
     * @param orderId 거래소 주문 ID
     * @return 원 주문 수량, 조회 실패 시 null
     */
    private String fetchOrderQty(String symbol, String orderId) {
        try {
            BinanceOrderResponse order = feignClient.getOrder(symbol, orderId, null);
            return order != null ? order.getOrigQty() : null;
        } catch (Exception e) {
            log.warn("[BINANCE 주문 수량 조회 실패] orderId={}: {}", orderId, e.getMessage());
            return null;
        }
    }

    /**
     * 청산 주문의 체결·펀딩 내역으로 청산 손익을 계산한다.
     * closedPnl = Σ realizedPnl − 진입 수수료 − Σ 청산 수수료 + Σ 펀딩비 수입(진입 시각 ~ 현재)
     * (GET /fapi/v1/userTrades 가중치 5 + GET /fapi/v1/income 가중치 30)
     *
     * @param query 조회 조건 (entryTime null이면 펀딩비 0, entryFee null이면 0)
     * @return 청산 손익 (fundingFee 양수: 지급), 청산 체결 미반영이면 null
     */
    @Override
    protected ClosedPnlDTO fetchClosedPnl(ClosedPnlQueryDTO query) {
        String symbol = query.getSymbol();
        List<BinanceUserTradeResponse> trades = feignClient.getUserTrades(symbol, query.getOrderId(), null, QUERY_LIMIT);
        // 청산 체결 미반영 → 재조회 대상
        if (trades == null || trades.isEmpty()) return null;

        BigDecimal realizedPnl = BigDecimal.ZERO;
        BigDecimal closeFee = BigDecimal.ZERO;
        for (BinanceUserTradeResponse t : trades) {
            realizedPnl = realizedPnl.add(toDecimal(t.getRealizedPnl()));
            closeFee = closeFee.add(toUsdtFee(t));
        }

        // 펀딩 수입 합 (양수: 수취, 음수: 지급) — 진입 시각을 모르면 0
        BigDecimal fundingIncome = BigDecimal.ZERO;
        if (query.getEntryTime() != null) {
            List<BinanceIncomeResponse> incomes = feignClient.getIncome(
                    symbol, "FUNDING_FEE", query.getEntryTime(), clock.millis(), QUERY_LIMIT);
            if (incomes != null) {
                for (BinanceIncomeResponse income : incomes) {
                    fundingIncome = fundingIncome.add(toDecimal(income.getIncome()));
                }
            }
        }

        BigDecimal entryFee = query.getEntryFee() != null ? query.getEntryFee() : BigDecimal.ZERO;
        BigDecimal closedPnl = realizedPnl.subtract(entryFee).subtract(closeFee).add(fundingIncome);
        return ClosedPnlDTO.builder()
                .orderId(query.getOrderId())
                .closedPnl(closedPnl.stripTrailingZeros().toPlainString())
                // 공통 규약: 펀딩비 양수 = 지급
                .fundingFee(fundingIncome.negate().stripTrailingZeros().toPlainString())
                .build();
    }

    /**
     * 기준 시각 이후 실현손익이 있는 가장 최근 체결의 주문을 청산 주문으로 보고,
     * 그 주문의 clientOrderId·origType으로 청산 방식을 판정한다.
     * - 청산 체결 없음: orderId null, closeType OTHER
     * - 주문 조회 결과 없음: orderId 유지, closeType OTHER
     * (GET /fapi/v1/userTrades 가중치 5 + GET /fapi/v1/order 가중치 1, 판정 불가 시 + GET /fapi/v1/allAlgoOrders 가중치 5)
     *
     * @param userId      API 키를 조회할 사용자 ID
     * @param symbol      종목 심볼
     * @param sinceMillis 검색 시작 시각 (epoch ms, 조회 범위 제한으로 최대 7일 전까지)
     * @return 청산 이벤트, 호출 실패 시 null (다음 재조정 주기에 재시도)
     */
    @Override
    public PositionCloseDTO findLastClose(Long userId, String symbol, long sinceMillis) {
        try {
            return withApiKey(userId, () -> {
                // userTrades startTime 조회는 최대 7일 범위 → 더 이전이면 7일 전으로 보정
                long startTime = Math.max(sinceMillis, clock.millis() - USER_TRADES_MAX_RANGE_MS);
                List<BinanceUserTradeResponse> trades = feignClient.getUserTrades(symbol, null, startTime, QUERY_LIMIT);

                // 실현손익이 있는(포지션을 줄인) 체결 중 가장 최근 건 선택 (진입 체결은 realizedPnl 0)
                BinanceUserTradeResponse latest = null;
                if (trades != null) {
                    for (BinanceUserTradeResponse t : trades) {
                        if (toDecimal(t.getRealizedPnl()).signum() == 0 || t.getTime() == null) continue;
                        if (latest == null || t.getTime() > latest.getTime()) latest = t;
                    }
                }

                PositionCloseDTO.PositionCloseDTOBuilder builder = PositionCloseDTO.builder()
                        .userId(userId)
                        .exchangeType(ExchangeType.BINANCE)
                        .symbol(symbol);
                // 청산 체결 없음 → 방식 불명
                if (latest == null) {
                    return builder.closeType(PositionCloseType.OTHER).build();
                }

                // 같은 청산 주문의 체결들로 가중 평균 청산가 계산
                String closeOrderId = latest.getOrderId();
                List<ExecutionDTO> closeExecs = trades.stream()
                        .filter(t -> closeOrderId.equals(t.getOrderId()))
                        .map(t -> toExecutionDTO(t, null))
                        .toList();
                builder.orderId(closeOrderId).execPrice(aggregateExecutions(closeExecs).getExecPrice());

                // 청산 주문 정보로 방식 판정
                BinanceOrderResponse order = feignClient.getOrder(symbol, closeOrderId, null);
                if (order == null) {
                    return builder.closeType(PositionCloseType.OTHER).build();
                }
                return builder
                        .orderLinkId(order.getClientOrderId())
                        .closeType(resolveCloseType(symbol, closeOrderId, order.getClientOrderId(),
                                order.getOrigType(), startTime))
                        .build();
            });
        } catch (Exception e) {
            log.warn("[BINANCE 청산 내역 조회 실패] symbol={}: {}", symbol, e.getMessage());
            return null;
        }
    }

    /**
     * 청산 주문의 청산 방식을 판정한다. (C안 — REST 재조정·user data stream 공용)
     * ① clientOrderId 접두어·원 주문 유형으로 판정 (API 호출 없음)
     * ② ①이 OTHER 면 조건부 주문 이력에서 actualOrderId == 청산 주문 ID 인 주문을 찾아 그 clientAlgoId·orderType 으로 판정
     *    (트리거된 TP/SL 주문의 clientOrderId·origType 이 거래소 임의 값일 수 있어 보강, GET /fapi/v1/allAlgoOrders 가중치 5)
     * ③ 그래도 판정 불가 또는 이력 조회 실패 → OTHER (청산 감지 자체는 유지)
     * 호출부에서 API 키가 바인딩돼 있어야 한다.
     *
     * @param symbol        종목 심볼
     * @param closeOrderId  청산 주문의 거래소 주문 ID
     * @param clientOrderId 청산 주문의 clientOrderId (null 가능)
     * @param origType      청산 주문의 원 주문 유형 (null 가능)
     * @param startTime     조건부 주문 이력 조회 시작 시각 epoch ms (최대 7일 범위로 보정)
     * @return 청산 방식
     */
    private PositionCloseType resolveCloseType(String symbol, String closeOrderId, String clientOrderId,
                                               String origType, long startTime) {
        // ① 주문 ID·유형으로 판정되면 바로 반환
        PositionCloseType type = BinanceCloseTypeResolver.resolve(clientOrderId, origType);
        if (type != PositionCloseType.OTHER || closeOrderId == null) return type;

        // ② 조건부 주문 이력에서 이 청산 주문을 만든 TP/SL 주문 탐색
        try {
            long from = Math.max(startTime, clock.millis() - USER_TRADES_MAX_RANGE_MS);
            List<BinanceAlgoOrderHistoryResponse> history = feignClient.getAllAlgoOrders(symbol, from, QUERY_LIMIT);
            if (history == null) return PositionCloseType.OTHER;
            for (BinanceAlgoOrderHistoryResponse algo : history) {
                if (closeOrderId.equals(algo.getActualOrderId())) {
                    return BinanceCloseTypeResolver.resolve(algo.getClientAlgoId(), algo.getOrderType());
                }
            }
        } catch (Exception e) {
            log.warn("[BINANCE 조건부 주문 이력 조회 실패] symbol={}, orderId={}: {}", symbol, closeOrderId, e.getMessage());
        }
        // ③ 판정 불가
        return PositionCloseType.OTHER;
    }

    /**
     * 심볼의 현재 포지션 수량을 조회한다. (GET /fapi/v3/positionRisk, 가중치 5)
     *
     * @param userId API 키를 조회할 사용자 ID
     * @param symbol 종목 심볼
     * @return 포지션 수량 절댓값 합계 (포지션 없으면 0), 호출 실패 시 null
     */
    @Override
    public BigDecimal getPositionSize(Long userId, String symbol) {
        try {
            return withApiKey(userId, () -> {
                List<BinancePositionRiskResponse> positions = feignClient.getPositionRisk(symbol);
                BigDecimal total = BigDecimal.ZERO;
                if (positions == null) return total;
                for (BinancePositionRiskResponse p : positions) {
                    // 숏은 음수이므로 절댓값 합산
                    total = total.add(toDecimal(p.getPositionAmt()).abs());
                }
                return total;
            });
        } catch (Exception e) {
            log.warn("[BINANCE 포지션 조회 실패] symbol={}: {}", symbol, e.getMessage());
            return null;
        }
    }

    // ─── private 스트림 ───

    /**
     * user data stream 연결 대상 사용자와 실제 연결을 동기화한다.
     * 신규 대상은 API 키를 복호화해 연결을 열고(listenKey 발급은 키 헤더만 필요), 대상에서 빠진 사용자는 연결을 닫는다.
     * 키가 없거나 복호화에 실패한 사용자는 건너뛴다. (해당 사용자는 REST 체결 조회·재조정으로 동작)
     *
     * @param userIds 연결을 유지할 사용자 ID 집합 (활성 MAIN 세션 보유자)
     */
    @Override
    public void syncPrivateStreams(Set<Long> userIds) {
        Set<Long> connected = privateWebSocketClient.getConnectedUserIds();

        // 대상에서 빠진 사용자 → 연결 종료
        for (Long userId : connected) {
            if (!userIds.contains(userId)) {
                privateWebSocketClient.close(userId);
            }
        }

        // 신규 대상 사용자 → 키 복호화 후 연결 (이미 연결된 사용자는 키 조회 생략)
        for (Long userId : userIds) {
            if (connected.contains(userId)) continue;
            try {
                ExchangeKeyDTO key = exchangeKeyService.findKey(userId, ExchangeType.BINANCE);
                privateWebSocketClient.open(userId, aesEncryptor.decrypt(key.getApiKey()));
            } catch (Exception e) {
                // 한 사용자의 키 문제로 다른 사용자 연결이 막히지 않도록 건너뜀 (키 값은 로그에 남기지 않음)
                log.warn(LogMessage.BINANCE_PRIVATE_WS_OPEN_SKIPPED.getMessage(), userId, e.getMessage());
            }
        }
    }

    /**
     * 개인 스트림에서 주문 업데이트를 받으면 호출된다. (WebSocket 수신 스레드)
     * 전량 체결된 청산 성격 주문만 청산 이벤트로 바꿔 리스너에 전달한다. → 주문당 1회
     * - 체결 이벤트(x=TRADE, 강제청산·ADL은 CALCULATED)이면서 X=FILLED 인 마지막 체결만 대상
     * - 청산 성격: 실현손익 ≠ 0, reduceOnly, closePosition, 거래소 개입 청산 접두어 중 하나 (진입 체결 제외)
     * - 앱이 보낸 청산 주문도 전달되며, 수신부가 orderLinkId 로 걸러낸다.
     * 청산 방식 판정은 REST 조회가 필요할 수 있어 별도 스레드에서 수행한다.
     *
     * @param userId 이벤트가 온 연결의 사용자 ID
     * @param order  주문 업데이트
     */
    void onOrderUpdate(Long userId, BinanceOrderTradeUpdateDTO.OrderUpdate order) {
        if (!CLOSE_EXEC_TYPES.contains(order.getExecType()) || !FILLED.equals(order.getOrderStatus())) return;
        if (!isCloseLike(order)) return;
        closeEventExecutor.execute(() -> publishStreamClose(userId, order));
    }

    /**
     * 스트림 청산 체결의 청산 방식을 판정해 청산 이벤트를 발행한다. (청산 이벤트 스레드)
     *
     * @param userId 사용자 ID
     * @param order  전량 체결된 청산 주문 업데이트
     */
    private void publishStreamClose(Long userId, BinanceOrderTradeUpdateDTO.OrderUpdate order) {
        PositionCloseType closeType;
        try {
            // 시작 시각 0 → 조건부 주문 이력 최대 범위(7일)로 보정됨
            closeType = withApiKey(userId, () -> resolveCloseType(order.getSymbol(), order.getOrderId(),
                    order.getClientOrderId(), order.getOrigType(), 0L));
        } catch (Exception e) {
            // 키 조회 실패 등 → 주문 ID·유형만으로 판정
            log.warn("[BINANCE 청산 방식 판정 실패] userId={}, orderId={}: {}", userId, order.getOrderId(), e.getMessage());
            closeType = BinanceCloseTypeResolver.resolve(order.getClientOrderId(), order.getOrigType());
        }

        publishPositionClose(PositionCloseDTO.builder()
                .userId(userId)
                .exchangeType(ExchangeType.BINANCE)
                .symbol(order.getSymbol())
                .orderId(order.getOrderId())
                .orderLinkId(order.getClientOrderId())
                // 주문 평균 체결가 우선, 없으면 마지막 체결가
                .execPrice(isPositive(order.getAvgPrice()) ? order.getAvgPrice() : order.getLastFilledPrice())
                .closeType(closeType)
                .build());
    }

    /** 포지션을 줄인 주문인지 판단한다. (실현손익 발생, 축소 전용, 전량 청산, 거래소 개입 청산) */
    private static boolean isCloseLike(BinanceOrderTradeUpdateDTO.OrderUpdate order) {
        if (toDecimal(order.getRealizedProfit()).signum() != 0) return true;
        if (order.isReduceOnly() || order.isClosePosition()) return true;
        String clientOrderId = order.getClientOrderId();
        return clientOrderId != null && EXCHANGE_CLOSE_PREFIXES.stream().anyMatch(clientOrderId::startsWith);
    }

    // ─── 변환 ───

    /**
     * 체결 1건을 공통 체결 정보로 변환한다. (수수료는 USDT 환산)
     *
     * @param trade    Binance 체결
     * @param orderQty 원 주문 수량 (null 가능)
     * @return 공통 체결 정보
     */
    private ExecutionDTO toExecutionDTO(BinanceUserTradeResponse trade, String orderQty) {
        return ExecutionDTO.builder()
                .symbol(trade.getSymbol())
                .orderId(trade.getOrderId())
                .side(trade.getSide() != null ? OrderSide.valueOf(trade.getSide()) : null)
                .execPrice(trade.getPrice())
                .execQty(trade.getQty())
                .execFee(toUsdtFee(trade).toPlainString())
                .orderQty(orderQty)
                .execTime(trade.getTime() != null ? String.valueOf(trade.getTime()) : null)
                .build();
    }

    /**
     * 스트림 집계 체결을 공통 체결 정보로 변환한다. (수수료는 USDT 환산)
     *
     * @param filled 스트림 집계 체결
     * @return 공통 체결 정보
     */
    private ExecutionDTO toExecutionDTO(BinanceFilledOrderDTO filled) {
        return ExecutionDTO.builder()
                .symbol(filled.getSymbol())
                .orderId(filled.getOrderId())
                .side(filled.getSide() != null ? OrderSide.valueOf(filled.getSide()) : null)
                .execPrice(filled.getAvgPrice())
                .execQty(filled.getFilledQty())
                .execFee(toUsdtFee(filled).toPlainString())
                .orderQty(filled.getOrderQty())
                .execTime(filled.getExecTime())
                .build();
    }

    /**
     * 스트림 집계 수수료를 USDT로 환산한다. (부호와 관계없이 절댓값)
     * - USDT 수수료 + BNB 수수료 × BNBUSDT 현재가 (시세 캐시 사용)
     * - BNB 시세 없음·그 외 자산 수수료 포함: 평균가 × 수량 × taker 요율로 추정 (자산별로 나눌 수 없어 전체 추정)
     *
     * @param filled 스트림 집계 체결
     * @return USDT 수수료
     */
    private BigDecimal toUsdtFee(BinanceFilledOrderDTO filled) {
        BigDecimal usdtFee = toDecimal(filled.getUsdtFee()).abs();
        BigDecimal bnbFee = toDecimal(filled.getBnbFee()).abs();
        if (!filled.isOtherFeeAsset()) {
            if (bnbFee.signum() == 0) return usdtFee;
            String bnbPrice = findLastPrice(BNB_PRICE_SYMBOL);
            if (bnbPrice != null) {
                log.warn("[BINANCE BNB 수수료 환산] orderId={}, commission={} BNB, price={}",
                        filled.getOrderId(), bnbFee.toPlainString(), bnbPrice);
                return usdtFee.add(bnbFee.multiply(new BigDecimal(bnbPrice)));
            }
        }
        BigDecimal estimated = toDecimal(filled.getAvgPrice()).multiply(toDecimal(filled.getFilledQty())).multiply(TAKER_FEE_RATE);
        log.warn("[BINANCE 수수료 추정] orderId={} — 환산 불가로 taker 요율 추정값 {} 사용",
                filled.getOrderId(), estimated.toPlainString());
        return estimated;
    }

    /**
     * 체결 수수료를 USDT로 환산한다. (부호와 관계없이 절댓값)
     * - USDT: 그대로
     * - BNB: × BNBUSDT 현재가 (시세 캐시 사용, 추가 API 호출 없음)
     * - BNB 시세 없음·그 외 자산: 체결가 × 수량 × taker 요율로 추정
     *
     * @param trade Binance 체결
     * @return USDT 수수료
     */
    private BigDecimal toUsdtFee(BinanceUserTradeResponse trade) {
        BigDecimal commission = toDecimal(trade.getCommission()).abs();
        String asset = trade.getCommissionAsset();
        if (asset == null || "USDT".equals(asset)) return commission;

        if ("BNB".equals(asset)) {
            String bnbPrice = findLastPrice(BNB_PRICE_SYMBOL);
            if (bnbPrice != null) {
                log.warn("[BINANCE BNB 수수료 환산] orderId={}, commission={} BNB, price={}",
                        trade.getOrderId(), commission.toPlainString(), bnbPrice);
                return commission.multiply(new BigDecimal(bnbPrice));
            }
        }
        BigDecimal estimated = toDecimal(trade.getPrice()).multiply(toDecimal(trade.getQty())).multiply(TAKER_FEE_RATE);
        log.warn("[BINANCE 수수료 추정] orderId={}, commission={} {} — 환산 불가로 taker 요율 추정값 {} 사용",
                trade.getOrderId(), commission.toPlainString(), asset, estimated.toPlainString());
        return estimated;
    }

    /**
     * 시세 캐시에서 심볼의 현재가를 찾는다.
     *
     * @param symbol 종목 심볼
     * @return 현재가, 없으면 null
     */
    private String findLastPrice(String symbol) {
        List<TickerDTO> tickers = marketClient.getTickers();
        if (tickers == null) return null;
        return tickers.stream()
                .filter(t -> symbol.equals(t.getSymbol()) && t.getLastPrice() != null && !t.getLastPrice().isBlank())
                .map(TickerDTO::getLastPrice)
                .findFirst()
                .orElse(null);
    }

    private static BigDecimal toDecimal(String value) {
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value);
    }

    private static boolean isPositive(String value) {
        return toDecimal(value).signum() > 0;
    }
}
