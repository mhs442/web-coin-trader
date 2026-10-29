package com.coin.webcointrader.bybit.trade;

import com.coin.webcointrader.bybit.trade.PositionClient;
import com.coin.webcointrader.bybit.stream.BybitExecutionWaiter;
import com.coin.webcointrader.bybit.stream.BybitPrivateWebSocketClient;
import com.coin.webcointrader.bybit.stream.dto.WebSocketExecutionDTO;
import com.coin.webcointrader.bybit.trade.dto.CancelOrderRequest;
import com.coin.webcointrader.bybit.trade.dto.CancelOrderResponse;
import com.coin.webcointrader.bybit.trade.dto.CreateOrderRequest;
import com.coin.webcointrader.bybit.trade.dto.SetLeverageRequest;
import com.coin.webcointrader.bybit.trade.dto.SetMarginModeRequest;
import com.coin.webcointrader.bybit.trade.dto.CreateOrderResponse;
import com.coin.webcointrader.bybit.trade.dto.GetClosedPnlResponse;
import com.coin.webcointrader.bybit.trade.dto.GetExecutionListResponse;
import com.coin.webcointrader.bybit.trade.dto.GetOpenOrdersResponse;
import com.coin.webcointrader.bybit.trade.dto.GetPositionListResponse;
import com.coin.webcointrader.bybit.trade.dto.SetLeverageResponse;
import com.coin.webcointrader.bybit.trade.dto.SetMarginModeResponse;
import com.coin.webcointrader.bybit.trade.BybitCloseTypeResolver;
import com.coin.webcointrader.common.enums.Category;
import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.enums.LogMessage;
import com.coin.webcointrader.common.enums.OrderCancelResult;
import com.coin.webcointrader.common.enums.OrderSide;
import com.coin.webcointrader.common.enums.PositionCloseType;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.trade.client.AbstractExchangeTradeClient;
import com.coin.webcointrader.common.trade.dto.PositionCloseDTO;
import com.coin.webcointrader.common.trade.dto.ClosedPnlDTO;
import com.coin.webcointrader.common.trade.dto.ClosedPnlQueryDTO;
import com.coin.webcointrader.common.trade.dto.ExecutionDTO;
import com.coin.webcointrader.common.trade.dto.OrderLookupDTO;
import com.coin.webcointrader.common.trade.dto.OrderRequestDTO;
import com.coin.webcointrader.common.trade.dto.OrderResultDTO;
import com.coin.webcointrader.common.util.AesEncryptor;
import com.coin.webcointrader.common.util.UserApiKeyContext;
import com.coin.webcointrader.common.login.dto.ExchangeKeyDTO;
import com.coin.webcointrader.common.login.service.ExchangeKeyService;
import feign.FeignException;
import feign.RetryableException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Bybit 거래 클라이언트.
 * 공통 주문 요청을 Bybit V5 REST(linear 무기한 선물)로 변환하고, 응답을 공통 DTO로 변환한다.
 * private WebSocket 체결 수신으로 체결 대기·외부 청산 감지를 제공한다.
 */
@Slf4j
@Component
public class BybitTradeClient extends AbstractExchangeTradeClient {

    // 체결 내역 페이지 크기 (Bybit 최대값)
    private static final int EXECUTION_PAGE_LIMIT = 100;
    // 체결 내역 최대 페이지 수 (무한 루프 방지)
    private static final int EXECUTION_MAX_PAGES = 10;
    // 청산 손익 조회 건수
    private static final int CLOSED_PNL_LIMIT = 50;
    // 청산 손익 조회 범위 (ms, 반영 지연 대비)
    private static final long CLOSED_PNL_LOOKBACK_MS = 5 * 60 * 1000L;
    // Bybit closed-pnl 조회 가능 최대 범위 (7일)
    private static final long CLOSED_PNL_MAX_RANGE_MS = 7L * 24 * 60 * 60 * 1000L;
    // 이미 격리 모드 (정상 처리)
    private static final String RET_CODE_MARGIN_NOT_MODIFIED = "110026";
    // 이미 같은 레버리지 (정상 처리)
    private static final String RET_CODE_LEVERAGE_NOT_MODIFIED = "110043";
    // 주문 없음 (이미 전부 체결·취소되어 취소 불가)
    private static final String RET_CODE_ORDER_NOT_EXISTS = "110001";
    // 포지션을 줄일 수 있는 체결 유형 (Trade: 일반, BustTrade: 강제청산, AdlTrade: ADL / Funding 등은 제외)
    // Linear(USDT 선물) 시장가 주문 taker 수수료율 (기본 등급 0.055%)
    private static final BigDecimal TAKER_FEE_RATE = new BigDecimal("0.00055");
    // Linear(USDT 선물) 지정가 주문 maker 수수료율 (기본 등급 0.02%)
    private static final BigDecimal MAKER_FEE_RATE = new BigDecimal("0.0002");
    private static final Set<String> CLOSE_EXEC_TYPES = Set.of("Trade", "BustTrade", "AdlTrade");

    private final TradeClient tradeClient;
    private final PositionClient positionClient;
    private final BybitExecutionWaiter bybitExecutionWaiter;
    private final BybitPrivateWebSocketClient bybitPrivateWebSocketClient;

    public BybitTradeClient(TradeClient tradeClient, PositionClient positionClient,
                            BybitExecutionWaiter bybitExecutionWaiter,
                            BybitPrivateWebSocketClient bybitPrivateWebSocketClient,
                            ExchangeKeyService exchangeKeyService, AesEncryptor aesEncryptor, Clock clock) {
        super(exchangeKeyService, aesEncryptor, clock);
        this.tradeClient = tradeClient;
        this.positionClient = positionClient;
        this.bybitExecutionWaiter = bybitExecutionWaiter;
        this.bybitPrivateWebSocketClient = bybitPrivateWebSocketClient;
    }

    /**
     * 빈 초기화 시 private WS 클라이언트에 체결 콜백을 등록한다. (청산 체결 → 포지션 청산 리스너 팬아웃)
     */
    @PostConstruct
    public void init() {
        bybitPrivateWebSocketClient.addExecutionCallback(this::onExecutionReceived);
    }

    @Override
    public ExchangeType getExchangeType() {
        return ExchangeType.BYBIT;
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
     * 공통 주문을 Bybit 시장가/지정가 주문으로 전송한다. (POST /v5/order/create)
     * price가 있으면 지정가(Limit, GTC), 없으면 시장가로 보낸다.
     * TP/SL이 있으면 시장가·최종 체결가(LastPrice) 트리거·포지션 전체(Full) 모드로 동봉한다.
     *
     * @param request 주문 요청 (clientOrderId → orderLinkId)
     * @return 주문 ID와 clientOrderId
     * @throws CustomException retCode가 "0"이 아니거나 응답 본문이 없는 경우 (ORDER_FAILED, "[retCode] retMsg" 형태로 거래소 코드·메시지 포함)
     */
    @Override
    protected OrderResultDTO submitOrder(OrderRequestDTO request) {
        CreateOrderRequest.CreateOrderRequestBuilder builder = CreateOrderRequest.builder()
                .category(Category.LINEAR.getCategory())
                .symbol(request.getSymbol())
                .side(toBybitSide(request.getSide()))
                .orderType(request.getPrice() != null ? "Limit" : "Market")
                .qty(request.getQty())
                .positionIdx(0) // 단방향 모드 명시 (헤지 모드 계정은 거래소가 거부)
                .orderLinkId(request.getClientOrderId());
        // 지정가: 가격 + GTC (취소·체결 전까지 호가창에 유지)
        if (request.getPrice() != null) {
            builder.price(request.getPrice()).timeInForce("GTC");
        }
        // 청산 주문만 reduceOnly 지정 (진입 주문은 필드 생략)
        if (request.isReduceOnly()) {
            builder.reduceOnly(true);
        }
        if (request.getTakeProfit() != null) {
            builder.takeProfit(request.getTakeProfit()).tpOrderType("Market").tpTriggerBy("LastPrice");
        }
        if (request.getStopLoss() != null) {
            builder.stopLoss(request.getStopLoss()).slOrderType("Market").slTriggerBy("LastPrice");
        }
        // TP 또는 SL이 하나라도 있을 때만 모드 지정
        if (request.getTakeProfit() != null || request.getStopLoss() != null) {
            builder.tpslMode("Full");
        }

        CreateOrderResponse response = tradeClient.createOrder(builder.build()).getBody();
        // Bybit 응답 에러 코드 검증 (원인 추적을 위해 "[retCode] retMsg" 형태로 기록)
        if (response == null || !"0".equals(response.getRetCode())) {
            throw new CustomException(ExceptionMessage.ORDER_FAILED,
                    response != null ? "[" + response.getRetCode() + "] " + response.getRetMsg() : "응답 없음");
        }
        return OrderResultDTO.builder()
                .orderId(response.getResult() != null ? response.getResult().getOrderId() : null)
                .clientOrderId(request.getClientOrderId())
                .build();
    }

    /**
     * 주문을 orderLinkId로 취소한다. (POST /v5/order/cancel)
     *
     * @param symbol        종목 심볼
     * @param clientOrderId 취소할 주문의 orderLinkId
     * @return retCode 0 → CANCELLED, 110001(주문 없음·이미 체결) → NOT_FOUND, 그 외·응답 없음 → FAILED
     */
    @Override
    protected OrderCancelResult doCancelOrder(String symbol, String clientOrderId) {
        CancelOrderResponse response = tradeClient.cancelOrder(CancelOrderRequest.builder()
                .category(Category.LINEAR.getCategory())
                .symbol(symbol)
                .orderLinkId(clientOrderId)
                .build()).getBody();
        if (response == null) return OrderCancelResult.FAILED;
        // retCode로 결과 판정
        if ("0".equals(response.getRetCode())) return OrderCancelResult.CANCELLED;
        if (RET_CODE_ORDER_NOT_EXISTS.equals(response.getRetCode())) return OrderCancelResult.NOT_FOUND;
        log.warn("[BYBIT 주문 취소] 거부 orderLinkId={}: [{}] {}", clientOrderId, response.getRetCode(), response.getRetMsg());
        return OrderCancelResult.FAILED;
    }

    /**
     * 읽기 타임아웃·연결 끊김(RetryableException)과 5xx는 거래소가 주문을 이미 접수했을 수 있다.
     * 4xx와 Feign 외 예외는 거부 확정으로 본다.
     */
    @Override
    protected boolean isAmbiguousFailure(RuntimeException e) {
        return e instanceof FeignException fe && (fe instanceof RetryableException || fe.status() >= 500);
    }

    /**
     * orderLinkId로 주문 상태를 조회한다. (GET /v5/order/realtime)
     * 체결 수량이 있으면(Filled, PartiallyFilledCanceled 포함) 포지션이 변동된 것이고, New/PartiallyFilled는 체결 진행 중이다.
     *
     * @param request 원 주문 요청
     * @return 주문 상태, 에러 응답·미반영이면 null
     */
    @Override
    protected OrderLookupDTO lookupOrder(OrderRequestDTO request) {
        GetOpenOrdersResponse body = tradeClient
                .getOpenOrders(Category.LINEAR.getCategory(), request.getSymbol(), request.getClientOrderId()).getBody();
        // 에러 응답 또는 미반영 → 재조회 대상
        if (body == null || !"0".equals(body.getRetCode())
                || body.getResult() == null || body.getResult().getList() == null
                || body.getResult().getList().isEmpty()) {
            return null;
        }
        GetOpenOrdersResponse.OrderInfo order = body.getResult().getList().get(0);
        boolean accepted = isPositive(order.getCumExecQty())
                || "New".equals(order.getOrderStatus()) || "PartiallyFilled".equals(order.getOrderStatus());
        return OrderLookupDTO.builder().orderId(order.getOrderId()).accepted(accepted).build();
    }

    // ─── 마진 모드 / 레버리지 ───

    /**
     * UTA 계정 마진 모드를 Isolated로 전환한다. (POST /v5/account/set-margin-mode)
     *
     * @param symbol 종목 심볼 (Bybit UTA는 계정 단위 설정이라 사용하지 않음)
     * @throws CustomException retCode가 "0"·"110026"(이미 Isolated)이 아닌 경우 (SWITCH_MARGIN_MODE_FAILED)
     */
    @Override
    protected void doSwitchToIsolated(String symbol) {
        SetMarginModeResponse response = positionClient
                .setMarginMode(new SetMarginModeRequest("ISOLATED_MARGIN")).getBody();
        if (response == null || !isOk(response.getRetCode(), RET_CODE_MARGIN_NOT_MODIFIED)) {
            // 원인 추적을 위해 "[retCode] retMsg" 형태로 기록
            throw new CustomException(ExceptionMessage.SWITCH_MARGIN_MODE_FAILED,
                    response != null ? "[" + response.getRetCode() + "] " + response.getRetMsg() : "응답 없음");
        }
    }

    /**
     * 심볼 레버리지를 롱·숏 동일하게 설정한다. (POST /v5/position/set-leverage)
     *
     * @param symbol   종목 심볼 (예: "BTCUSDT")
     * @param leverage 레버리지 (예: "10")
     * @throws CustomException retCode가 "0"·"110043"(동일 값)이 아닌 경우 (SET_LEVERAGE_FAILED)
     */
    @Override
    protected void doSetLeverage(String symbol, String leverage) {
        SetLeverageResponse response = positionClient.setLeverage(
                new SetLeverageRequest(Category.LINEAR.getCategory(), symbol, leverage, leverage)).getBody();
        if (response == null || !isOk(response.getRetCode(), RET_CODE_LEVERAGE_NOT_MODIFIED)) {
            // 원인 추적을 위해 "[retCode] retMsg" 형태로 기록
            throw new CustomException(ExceptionMessage.SET_LEVERAGE_FAILED,
                    response != null ? "[" + response.getRetCode() + "] " + response.getRetMsg() : "응답 없음");
        }
    }

    // ─── 체결 / 손익 조회 ───

    @Override
    public boolean expectExecution(Long userId, String clientOrderId) {
        return bybitExecutionWaiter.expect(userId, clientOrderId);
    }

    @Override
    public void cancelExecution(String clientOrderId) {
        bybitExecutionWaiter.cancel(clientOrderId);
    }

    @Override
    protected ExecutionDTO awaitExecution(String clientOrderId, long waitMillis) {
        GetExecutionListResponse.ExecutionInfo info = bybitExecutionWaiter.await(clientOrderId, waitMillis);
        return info != null ? toExecutionDTO(info) : null;
    }

    /**
     * 주문의 체결 내역을 전부 조회한다. (GET /v5/execution/list)
     *
     * @param orderId 거래소 주문 ID
     * @param symbol  종목 심볼
     * @return 체결 레코드 목록 (미반영·에러 응답 시 빈 목록)
     */
    @Override
    protected List<ExecutionDTO> fetchExecutions(String orderId, String symbol) {
        return fetchAllExecutions(orderId, symbol).stream().map(this::toExecutionDTO).toList();
    }

    /**
     * 최근 {@value #CLOSED_PNL_LOOKBACK_MS}ms의 linear 청산 손익을 최근 {@value #CLOSED_PNL_LIMIT}건 조회해
     * 주문 ID 일치 건을 찾는다. (GET /v5/position/closed-pnl)
     * Bybit는 펀딩비를 따로 주지 않으므로 구성값으로 역산해 채운다.
     * closedPnl에 수수료·펀딩비가 반영돼 있다고 보고 조회 조건의 entryTime·entryFee는 사용하지 않는다.
     * (API 문서 미기재 — 실거래 내역과 대조 검증 필요)
     *
     * @param query 조회 조건 (orderId: 청산 주문 ID, symbol: 종목 심볼만 사용)
     * @return 청산 손익, 에러 응답이거나 일치 건이 없으면(미반영) null
     */
    @Override
    protected ClosedPnlDTO fetchClosedPnl(ClosedPnlQueryDTO query) {
        String orderId = query.getOrderId();
        String symbol = query.getSymbol();
        long startTime = clock.millis() - CLOSED_PNL_LOOKBACK_MS;
        GetClosedPnlResponse body = positionClient
                .getClosedPnl(Category.LINEAR.getCategory(), symbol, CLOSED_PNL_LIMIT, startTime).getBody();
        // 에러 응답 또는 결과 누락 → 재조회 대상
        if (body == null || !"0".equals(body.getRetCode())
                || body.getResult() == null || body.getResult().getList() == null) {
            return null;
        }
        // 청산 주문 ID 일치 건 탐색 (없으면 미반영 → 재조회 대상)
        return body.getResult().getList().stream()
                .filter(info -> orderId.equals(info.getOrderId()))
                .findFirst()
                .map(this::toClosedPnlDTO)
                .orElse(null);
    }

    /**
     * 기준 시각 이후 가장 최근 청산 건을 찾고, 그 주문의 체결 내역으로 청산 방식을 판정한다.
     * - 청산 건 없음: orderId null, closeType OTHER
     * - 체결 내역 없음: orderId 유지, closeType OTHER
     *
     * @param userId      API 키를 조회할 사용자 ID
     * @param symbol      종목 심볼
     * @param sinceMillis 검색 시작 시각 (epoch ms, Bybit 조회 범위 제한으로 최대 7일 전까지)
     * @return 청산 이벤트, closed-pnl 에러 응답·호출 실패 시 null (다음 재조정 주기에 재시도)
     */
    @Override
    public PositionCloseDTO findLastClose(Long userId, String symbol, long sinceMillis) {
        try {
            return withApiKey(userId, () -> {
                // Bybit closed-pnl은 최대 7일 범위 → 더 이전이면 7일 전으로 보정
                long startTime = Math.max(sinceMillis, clock.millis() - CLOSED_PNL_MAX_RANGE_MS);
                GetClosedPnlResponse body = positionClient
                        .getClosedPnl(Category.LINEAR.getCategory(), symbol, CLOSED_PNL_LIMIT, startTime).getBody();
                // 에러 응답 또는 결과 누락 → 판단 불가
                if (body == null || !"0".equals(body.getRetCode())
                        || body.getResult() == null || body.getResult().getList() == null) {
                    log.warn("[청산 내역 조회 실패] symbol={}, retCode={}", symbol, body != null ? body.getRetCode() : null);
                    return null;
                }

                // 기준 시각 이후 청산 건 중 가장 최근 건 선택 (createdTime 누락·파싱 불가 건은 제외)
                GetClosedPnlResponse.ClosedPnlInfo latest = null;
                long latestTime = Long.MIN_VALUE;
                for (GetClosedPnlResponse.ClosedPnlInfo info : body.getResult().getList()) {
                    long createdTime;
                    try {
                        createdTime = Long.parseLong(info.getCreatedTime());
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    if (createdTime >= sinceMillis && createdTime > latestTime) {
                        latest = info;
                        latestTime = createdTime;
                    }
                }

                PositionCloseDTO.PositionCloseDTOBuilder builder = PositionCloseDTO.builder()
                        .userId(userId)
                        .exchangeType(ExchangeType.BYBIT)
                        .symbol(symbol);
                // 청산 건 없음 → 방식 불명
                if (latest == null) {
                    return builder.closeType(PositionCloseType.OTHER).build();
                }

                builder.orderId(latest.getOrderId()).execPrice(latest.getAvgExitPrice());
                // 청산 주문의 체결 내역으로 방식 판정 (체결 레코드는 모두 같은 주문이므로 첫 건 기준)
                List<GetExecutionListResponse.ExecutionInfo> execs = fetchAllExecutions(latest.getOrderId(), symbol);
                if (execs.isEmpty()) {
                    return builder.closeType(PositionCloseType.OTHER).build();
                }
                GetExecutionListResponse.ExecutionInfo first = execs.get(0);
                return builder
                        .orderLinkId(first.getOrderLinkId())
                        .closeType(BybitCloseTypeResolver.resolve(
                                first.getExecType(), first.getStopOrderType(), first.getCreateType()))
                        .build();
            });
        } catch (Exception e) {
            log.warn("[청산 내역 조회 실패] symbol={}: {}", symbol, e.getMessage());
            return null;
        }
    }

    /**
     * 심볼의 현재 포지션 수량을 조회한다. (GET /v5/position/list, 단방향 모드는 심볼당 1건)
     *
     * @param userId API 키를 조회할 사용자 ID
     * @param symbol 종목 심볼
     * @return 포지션 수량 합계 (포지션 없으면 0), 에러 응답·호출 실패 시 null
     */
    @Override
    public BigDecimal getPositionSize(Long userId, String symbol) {
        try {
            return withApiKey(userId, () -> {
                GetPositionListResponse body = positionClient
                        .getPositionList(Category.LINEAR.getCategory(), symbol).getBody();
                // 에러 응답 또는 결과 누락 → 판단 불가
                if (body == null || !"0".equals(body.getRetCode())
                        || body.getResult() == null || body.getResult().getList() == null) {
                    log.warn("[포지션 조회 실패] symbol={}, retCode={}", symbol, body != null ? body.getRetCode() : null);
                    return null;
                }
                BigDecimal total = BigDecimal.ZERO;
                for (GetPositionListResponse.Result.PositionInfo info : body.getResult().getList()) {
                    // size 누락 건은 0으로 간주
                    if (info.getSize() != null && !info.getSize().isBlank()) {
                        total = total.add(new BigDecimal(info.getSize()).abs());
                    }
                }
                return total;
            });
        } catch (Exception e) {
            log.warn("[포지션 조회 실패] symbol={}: {}", symbol, e.getMessage());
            return null;
        }
    }

    // ─── private 스트림 ───

    /**
     * private 스트림(체결 수신) 연결 대상 사용자와 실제 연결을 동기화한다.
     * 신규 대상은 API 키를 복호화해 연결을 열고, 대상에서 빠진 사용자는 연결을 닫는다.
     * 키가 없거나 복호화에 실패한 사용자는 건너뛴다. (해당 사용자는 REST 체결 조회로 동작)
     *
     * @param userIds 연결을 유지할 사용자 ID 집합 (활성 MAIN 세션 보유자)
     */
    @Override
    public void syncPrivateStreams(Set<Long> userIds) {
        Set<Long> connected = bybitPrivateWebSocketClient.getConnectedUserIds();

        // 대상에서 빠진 사용자 → 연결 종료
        for (Long userId : connected) {
            if (!userIds.contains(userId)) {
                bybitPrivateWebSocketClient.close(userId);
            }
        }

        // 신규 대상 사용자 → 키 복호화 후 연결 (이미 연결된 사용자는 키 조회 생략)
        for (Long userId : userIds) {
            if (connected.contains(userId)) continue;
            try {
                ExchangeKeyDTO key = exchangeKeyService.findKey(userId, ExchangeType.BYBIT);
                bybitPrivateWebSocketClient.open(userId,
                        aesEncryptor.decrypt(key.getApiKey()),
                        aesEncryptor.decrypt(key.getApiSecret()));
            } catch (Exception e) {
                // 한 사용자의 키 문제로 다른 사용자 연결이 막히지 않도록 건너뜀 (키 값은 로그에 남기지 않음)
                log.warn(LogMessage.PRIVATE_WS_OPEN_SKIPPED.getMessage(), userId, e.getMessage());
            }
        }
    }

    /**
     * private WS에서 체결을 수신하면 호출된다. (WebSocket 수신 스레드)
     * 포지션을 줄인 체결(closedSize > 0) 중 주문의 마지막 체결만 청산 이벤트로 바꿔 리스너에 전달한다.
     * - 진입 체결(closedSize 0), 펀딩비 정산(Funding)은 제외
     * - 부분 체결 중간 건(leavesQty > 0)은 제외 → 주문당 1회 전달 (leavesQty 누락 시에는 전달, 중복은 수신부에서 무해)
     *
     * @param userId 체결이 발생한 연결의 사용자 ID
     * @param data   체결 건
     */
    private void onExecutionReceived(Long userId, WebSocketExecutionDTO.ExecutionData data) {
        if (!CLOSE_EXEC_TYPES.contains(data.getExecType())) return;
        // 포지션을 줄이지 않은 체결(진입) 제외
        if (!isPositive(data.getClosedSize())) return;
        // 잔량이 남은 부분 체결 중간 건 제외
        if (isPositive(data.getLeavesQty())) return;

        publishPositionClose(PositionCloseDTO.builder()
                .userId(userId)
                .exchangeType(ExchangeType.BYBIT)
                .symbol(data.getSymbol())
                .orderId(data.getOrderId())
                .orderLinkId(data.getOrderLinkId())
                .execPrice(data.getExecPrice())
                .closeType(BybitCloseTypeResolver.resolve(
                        data.getExecType(), data.getStopOrderType(), data.getCreateType()))
                .build());
    }

    // ─── 내부 헬퍼 ───

    /**
     * 체결 내역을 nextPageCursor를 따라 최대 {@value #EXECUTION_MAX_PAGES}페이지까지 모은다.
     * (API 키가 바인딩된 상태에서 호출해야 한다)
     */
    private List<GetExecutionListResponse.ExecutionInfo> fetchAllExecutions(String orderId, String symbol) {
        List<GetExecutionListResponse.ExecutionInfo> all = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < EXECUTION_MAX_PAGES; page++) {
            GetExecutionListResponse body = tradeClient
                    .getExecutionList(Category.LINEAR.getCategory(), symbol, orderId, EXECUTION_PAGE_LIMIT, cursor)
                    .getBody();
            // 에러 응답 또는 빈 결과 → 수집 종료
            if (body == null || !"0".equals(body.getRetCode())
                    || body.getResult() == null || body.getResult().getList() == null) {
                break;
            }
            all.addAll(body.getResult().getList());

            // 다음 페이지 커서가 없으면 마지막 페이지
            cursor = body.getResult().getNextPageCursor();
            if (cursor == null || cursor.isBlank()) break;
        }
        return all;
    }

    private ExecutionDTO toExecutionDTO(GetExecutionListResponse.ExecutionInfo info) {
        return ExecutionDTO.builder()
                .symbol(info.getSymbol())
                .orderId(info.getOrderId())
                .side(toOrderSide(info.getSide()))
                .execPrice(info.getExecPrice())
                .execQty(info.getExecQty())
                .execFee(info.getExecFee())
                .orderQty(info.getOrderQty())
                .execTime(info.getExecTime())
                .build();
    }

    private ClosedPnlDTO toClosedPnlDTO(GetClosedPnlResponse.ClosedPnlInfo info) {
        return ClosedPnlDTO.builder()
                .orderId(info.getOrderId())
                .closedPnl(info.getClosedPnl())
                .fundingFee(calcFundingFee(info))
                .build();
    }

    /**
     * 청산 손익 구성값으로 펀딩비를 역산한다. (closedPnl = 매매차익 - 진입 수수료 - 청산 수수료 - 펀딩비)
     * 매매차익은 청산 주문 방향으로 판단한다. (Sell: 롱 청산 → 청산가치-진입가치, Buy: 숏 청산 → 진입가치-청산가치)
     *
     * @param info Bybit 청산 손익
     * @return 펀딩비 (양수: 지급, 음수: 수취), 필요한 값이 없으면 null
     */
    private static String calcFundingFee(GetClosedPnlResponse.ClosedPnlInfo info) {
        // 역산 필수 값 누락 시 계산 불가
        if (info.getCumEntryValue() == null || info.getCumExitValue() == null
                || info.getOpenFee() == null || info.getCloseFee() == null
                || info.getClosedPnl() == null || info.getSide() == null) {
            return null;
        }
        BigDecimal entryValue = new BigDecimal(info.getCumEntryValue());
        BigDecimal exitValue = new BigDecimal(info.getCumExitValue());
        // 청산 주문 방향 기준 매매차익
        BigDecimal grossPnl = toOrderSide(info.getSide()) == OrderSide.SELL
                ? exitValue.subtract(entryValue)
                : entryValue.subtract(exitValue);
        return grossPnl
                .subtract(new BigDecimal(info.getOpenFee()))
                .subtract(new BigDecimal(info.getCloseFee()))
                .subtract(new BigDecimal(info.getClosedPnl()))
                .stripTrailingZeros().toPlainString();
    }

    /** 공통 주문 방향 → Bybit side ("Buy"/"Sell") */
    private static String toBybitSide(OrderSide side) {
        return side == OrderSide.BUY ? "Buy" : "Sell";
    }

    /** Bybit side → 공통 주문 방향 (알 수 없는 값은 null) */
    private static OrderSide toOrderSide(String side) {
        if ("Buy".equals(side)) return OrderSide.BUY;
        if ("Sell".equals(side)) return OrderSide.SELL;
        return null;
    }

    /** retCode가 성공("0") 또는 허용 코드인지 판단한다. */
    private static boolean isOk(String retCode, String allowedCode) {
        return "0".equals(retCode) || allowedCode.equals(retCode);
    }

    /** 숫자 문자열이 0보다 큰지 판단한다. (null·빈 값·파싱 불가는 false) */
    private static boolean isPositive(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            return new BigDecimal(value).signum() > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
