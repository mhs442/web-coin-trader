package com.coin.webcointrader.trade.service;

import com.coin.webcointrader.bybit.client.position.PositionClient;
import com.coin.webcointrader.bybit.client.privatews.BybitExecutionWaiter;
import com.coin.webcointrader.bybit.client.trade.TradeClient;
import com.coin.webcointrader.bybit.dto.request.CreateOrderRequest;
import com.coin.webcointrader.bybit.dto.request.SetLeverageRequest;
import com.coin.webcointrader.bybit.dto.request.SetTradingStopRequest;
import com.coin.webcointrader.bybit.dto.request.SetMarginModeRequest;
import com.coin.webcointrader.bybit.dto.response.CreateOrderResponse;
import com.coin.webcointrader.bybit.dto.response.GetClosedPnlResponse;
import com.coin.webcointrader.bybit.dto.response.GetExecutionListResponse;
import com.coin.webcointrader.bybit.dto.response.GetOpenOrdersResponse;
import com.coin.webcointrader.bybit.dto.response.GetPositionListResponse;
import com.coin.webcointrader.bybit.util.BybitCloseTypeResolver;
import com.coin.webcointrader.common.enums.PositionCloseType;
import com.coin.webcointrader.common.exchange.PositionCloseDTO;
import com.coin.webcointrader.bybit.dto.response.SetLeverageResponse;
import com.coin.webcointrader.bybit.dto.response.SetMarginModeResponse;
import com.coin.webcointrader.bybit.dto.response.SetTradingStopResponse;
import com.coin.webcointrader.trade.dto.TradeHistoryDTO;
import com.coin.webcointrader.login.dto.ExchangeKeyDTO;
import com.coin.webcointrader.common.enums.Category;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.enums.OrderResult;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.login.service.ExchangeKeyService;
import com.coin.webcointrader.common.util.AesEncryptor;
import com.coin.webcointrader.common.util.UserApiKeyContext;
import feign.FeignException;
import feign.RetryableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bybit 주문 실행 서비스.
 * 사용자의 API Key/Secret을 복호화하여 주문을 전송하고, 요청 컨텍스트를 안전하게 정리한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TradeService {

    private final TradeClient tradeClient;
    private final PositionClient positionClient;
    private final TradeHistoryService tradeHistoryService;
    private final ExchangeKeyService exchangeKeyService;
    private final AesEncryptor aesEncryptor;
    private final Clock clock;
    private final BybitExecutionWaiter bybitExecutionWaiter;

    // 마진 모드/레버리지 설정 캐시 유효 시간 (ms, 앱에서 직접 변경한 경우 최대 이 시간 내 재설정)
    private static final long SETTING_CACHE_TTL_MS = 10 * 60 * 1000L;
    // Isolated 전환 완료 시각 캐시 (key: userId, value: 설정 시각 ms)
    private final Map<Long, Long> isolatedCache = new ConcurrentHashMap<>();
    // 레버리지 설정 캐시 (key: userId:symbol)
    private final Map<String, LeverageSetting> leverageCache = new ConcurrentHashMap<>();

    /**
     * 마지막으로 설정 완료한 레버리지 정보.
     */
    private record LeverageSetting(
            String buyLeverage,  // 매수(Long) 레버리지
            String sellLeverage, // 매도(Short) 레버리지
            long setAt           // 설정 시각 (ms)
    ) {}

    // 체결 내역 조회 페이지당 건수 (Bybit 최대 100)
    private static final int EXECUTION_PAGE_LIMIT = 100;
    // 체결 내역 최대 조회 페이지 수 (무한 루프 방지)
    private static final int EXECUTION_MAX_PAGES = 10;
    // 청산 손익 조회 건수 (동시 청산된 다른 큐 건 사이에서 orderId 매칭용)
    private static final int CLOSED_PNL_LIMIT = 50;
    // 청산 손익 조회 범위 (ms, 반영 지연 대비)
    private static final long CLOSED_PNL_LOOKBACK_MS = 5 * 60 * 1000L;
    // closed-pnl 조회 가능 최대 범위 (Bybit 제한: startTime~endTime 7일)
    private static final long CLOSED_PNL_MAX_RANGE_MS = 7L * 24 * 60 * 60 * 1000L;
    // 결과 불명확 주문의 orderLinkId 조회 횟수 (거래소 반영 지연 대비)
    private static final int ORDER_LOOKUP_ATTEMPTS = 2;
    // orderLinkId 재조회 대기 시간 (ms)
    private static final long ORDER_LOOKUP_DELAY_MS = 500L;
    // WS 체결 최대 대기 시간 (ms) — 초과 시 REST 체결 조회로 fallback
    private static final long WS_EXECUTION_WAIT_MS = 2000L;

    /**
     * Bybit에 주문을 실행한다. (수동 주문용 - TradeHistory 없음)
     * retCode 검증만 수행하고, 실패 시 예외를 던진다.
     *
     * @param request 주문 요청 (symbol, side, orderType, qty 등 포함, category 생략 가능)
     * @param userId  API Key를 조회할 사용자 ID
     * @return 주문 생성 응답 (Bybit 주문 ID 포함)
     * @throws CustomException 주문 실패 시 (ORDER_FAILED)
     */
    public CreateOrderResponse placeOrder(CreateOrderRequest request, Long userId) {
        return placeOrder(request, userId, null);
    }

    /**
     * Bybit에 주문을 실행한다.
     * category가 없으면 LINEAR로 기본 설정하고,
     * 사용자 API Key/Secret을 복호화하여 {@link com.coin.webcointrader.common.util.UserApiKeyContext}에 설정 후 주문을 전송한다.
     * Bybit 응답의 retCode가 "0"이 아니면 거래 히스토리에 실패 이력을 저장한 후 예외를 던진다.
     * 타임아웃·5xx로 결과가 불명확하면 orderLinkId로 주문 상태를 조회해,
     * 접수·체결이 확인되면 성공 응답을 반환하고 아니면 원래 예외를 던진다.
     *
     * @param request 주문 요청 (symbol, side, orderType, qty 등 포함, category 생략 가능, orderLinkId 지정 시 결과 조회 가능)
     * @param userId  API Key를 조회할 사용자 ID
     * @param history 거래 이력 DTO (실패 시 저장용, null 가능)
     * @return 주문 생성 응답 (Bybit 주문 ID 포함)
     * @throws CustomException 주문 실패 시 (ORDER_FAILED), 사용자를 찾을 수 없는 경우 (USER_NOT_FOUND)
     */
    public CreateOrderResponse placeOrder(CreateOrderRequest request, Long userId, TradeHistoryDTO history) {
        // category가 없으면 LINEAR로 기본 설정 (나머지 필드는 그대로 복사 — orderLinkId·TP/SL 유실 방지)
        if (request.getCategory() == null) {
            request = request.toBuilder()
                    .category(Category.LINEAR.getCategory())
                    .build();
        }

        setApiKeyContext(userId);
        try {
            CreateOrderResponse response;
            try {
                response = tradeClient.createOrder(request).getBody();
            } catch (FeignException e) {
                // 타임아웃·5xx는 거래소 접수 여부가 불명확 → orderLinkId로 실제 결과 확인 (4xx는 거부 확정)
                CreateOrderResponse recovered = isAmbiguousFailure(e) ? recoverByOrderLinkId(request) : null;
                if (recovered == null) throw e;
                return recovered;
            }

            // Bybit 응답 에러 코드 검증
            if (response == null || !"0".equals(response.getRetCode())) {
                String errorMsg = response != null ? response.getRetMsg() : "응답 없음";
                // 실패 이력 저장 후 예외 (자동매매 호출 시에만 history 존재)
                if (history != null) {
                    history.setExecutedPrice(BigDecimal.ZERO);
                    history.setOrderResult(OrderResult.FAILED);
                    history.setErrorMessage(errorMsg);
                    tradeHistoryService.saveTrade(history);
                }
                throw new CustomException(ExceptionMessage.ORDER_FAILED, errorMsg);
            }

            return response;
        } finally {
            UserApiKeyContext.clear();
        }
    }

    /**
     * 주문 결과가 불명확한 실패인지 판단한다.
     * 읽기 타임아웃·연결 끊김(RetryableException)과 5xx는 거래소가 주문을 이미 접수했을 수 있다.
     *
     * @param e Feign 호출 예외
     * @return 결과 불명확이면 true
     */
    private boolean isAmbiguousFailure(FeignException e) {
        return e instanceof RetryableException || e.status() >= 500;
    }

    /**
     * orderLinkId로 주문 상태를 조회해 실제 접수·체결 여부를 확인한다.
     * 거래소 반영 지연을 고려해 최대 {@value #ORDER_LOOKUP_ATTEMPTS}회 조회한다.
     * (API Key 컨텍스트가 설정된 상태에서 호출해야 한다)
     *
     * @param request 원 주문 요청 (orderLinkId가 없으면 조회 불가)
     * @return 접수·체결이 확인되면 orderId를 담은 성공 응답(retCode "0"), 미확인·거부·조회 실패 시 null
     */
    private CreateOrderResponse recoverByOrderLinkId(CreateOrderRequest request) {
        String orderLinkId = request.getOrderLinkId();
        // orderLinkId 없는 주문(수동 주문)은 조회 불가
        if (orderLinkId == null) return null;

        for (int attempt = 0; attempt < ORDER_LOOKUP_ATTEMPTS; attempt++) {
            if (attempt > 0) {
                try { Thread.sleep(ORDER_LOOKUP_DELAY_MS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            try {
                GetOpenOrdersResponse body = tradeClient
                        .getOpenOrders(request.getCategory(), request.getSymbol(), orderLinkId).getBody();
                // 에러 응답 또는 미반영 → 재조회
                if (body == null || !"0".equals(body.getRetCode())
                        || body.getResult() == null || body.getResult().getList() == null
                        || body.getResult().getList().isEmpty()) {
                    continue;
                }

                GetOpenOrdersResponse.OrderInfo order = body.getResult().getList().get(0);
                // 거부·미체결 취소 → 실패 확정
                if (!isAccepted(order)) {
                    log.warn("[주문 결과 확인] 주문 미체결 확정 orderLinkId={}, status={}", orderLinkId, order.getOrderStatus());
                    return null;
                }

                log.warn("[주문 결과 확인] 응답 수신 실패했으나 주문 접수 확인 orderLinkId={}, orderId={}, status={}",
                        orderLinkId, order.getOrderId(), order.getOrderStatus());
                CreateOrderResponse.Result result = new CreateOrderResponse.Result();
                result.setOrderId(order.getOrderId());
                result.setOrderLinkId(orderLinkId);
                CreateOrderResponse recovered = new CreateOrderResponse();
                recovered.setRetCode("0");
                recovered.setRetMsg("OK");
                recovered.setResult(result);
                return recovered;
            } catch (Exception e) {
                // 조회 자체 실패 → 재조회 (최종 실패 시 원래 예외로 처리)
                log.warn("[주문 결과 확인] 조회 실패 orderLinkId={}: {}", orderLinkId, e.getMessage());
            }
        }
        log.warn("[주문 결과 확인] 주문을 찾을 수 없음 orderLinkId={} — 실패로 처리", orderLinkId);
        return null;
    }

    /**
     * 주문이 거래소에 접수·체결되었는지 판단한다.
     * 체결 수량이 있으면(Filled, PartiallyFilledCanceled 포함) 포지션이 변동된 것이고,
     * New/PartiallyFilled는 접수되어 체결 진행 중이다.
     *
     * @param order 조회된 주문 정보
     * @return 접수·체결이면 true, Rejected/Cancelled(체결 0) 등은 false
     */
    private boolean isAccepted(GetOpenOrdersResponse.OrderInfo order) {
        String cumExecQty = order.getCumExecQty();
        if (cumExecQty != null && !cumExecQty.isBlank() && new BigDecimal(cumExecQty).signum() > 0) {
            return true;
        }
        return "New".equals(order.getOrderStatus()) || "PartiallyFilled".equals(order.getOrderStatus());
    }

    /**
     * UTA 계정의 마진 모드를 Isolated로 전환한다.
     * 이미 Isolated 모드인 경우(retCode "110026")는 정상 처리한다.
     * 전환 성공 후 유효 시간({@value #SETTING_CACHE_TTL_MS}ms) 동안은 Bybit 호출 없이 성공 응답을 반환한다.
     *
     * @param userId API Key를 조회할 사용자 ID
     * @return 마진 모드 설정 결과 응답 (캐시 적중 시 retCode "0")
     * @throws CustomException 마진 모드 변경 실패 시 (SWITCH_MARGIN_MODE_FAILED)
     */
    public SetMarginModeResponse switchToIsolated(Long userId) {
        long now = clock.millis();
        // 유효 시간 내 전환 완료 이력 있음 → API 호출 생략 (진입 지연·호출 한도 절감)
        Long setAt = isolatedCache.get(userId);
        if (setAt != null && now - setAt <= SETTING_CACHE_TTL_MS) {
            SetMarginModeResponse cached = new SetMarginModeResponse();
            cached.setRetCode("0");
            cached.setRetMsg("OK (cached)");
            return cached;
        }

        setApiKeyContext(userId);
        try {
            SetMarginModeRequest request = new SetMarginModeRequest("ISOLATED_MARGIN");
            SetMarginModeResponse response = positionClient.setMarginMode(request).getBody();

            // Bybit 응답 에러 코드 검증 (110026: 이미 동일 마진 모드 설정됨 → 무시)
            if (response == null || (!"0".equals(response.getRetCode()) && !"110026".equals(response.getRetCode()))) {
                String errorMsg = response != null ? response.getRetMsg() : "응답 없음";
                throw new CustomException(ExceptionMessage.SWITCH_MARGIN_MODE_FAILED, errorMsg);
            }

            // 성공 시에만 캐시 (실패하면 다음 진입에서 재시도)
            isolatedCache.put(userId, now);
            return response;
        } finally {
            UserApiKeyContext.clear();
        }
    }

    /**
     * Bybit에 레버리지를 설정한다.
     * 사용자 API Key 컨텍스트를 설정한 후 레버리지 변경 API를 호출한다.
     * retCode "110043"(이미 동일 레버리지)은 정상 처리한다.
     * 같은 사용자·심볼에 같은 레버리지를 설정한 지 유효 시간({@value #SETTING_CACHE_TTL_MS}ms) 이내면
     * Bybit 호출 없이 성공 응답을 반환한다.
     *
     * @param request 레버리지 설정 요청 (category, symbol, buyLeverage, sellLeverage)
     * @param userId  API Key를 조회할 사용자 ID
     * @return 레버리지 설정 결과 응답 (캐시 적중 시 retCode "0")
     * @throws CustomException 레버리지 설정 실패 시 (SET_LEVERAGE_FAILED)
     */
    public SetLeverageResponse setLeverage(SetLeverageRequest request, Long userId) {
        long now = clock.millis();
        String cacheKey = userId + ":" + request.getSymbol();
        // 유효 시간 내 동일 레버리지 설정 이력 있음 → API 호출 생략 (값이 다르면 재설정)
        LeverageSetting cachedSetting = leverageCache.get(cacheKey);
        if (cachedSetting != null
                && now - cachedSetting.setAt() <= SETTING_CACHE_TTL_MS
                && cachedSetting.buyLeverage().equals(request.getBuyLeverage())
                && cachedSetting.sellLeverage().equals(request.getSellLeverage())) {
            SetLeverageResponse cached = new SetLeverageResponse();
            cached.setRetCode("0");
            cached.setRetMsg("OK (cached)");
            return cached;
        }

        setApiKeyContext(userId);
        try {
            SetLeverageResponse response = positionClient.setLeverage(request).getBody();

            // Bybit 응답 에러 코드 검증 (110043: 이미 동일 레버리지 설정됨 → 무시)
            if (response == null || (!"0".equals(response.getRetCode()) && !"110043".equals(response.getRetCode()))) {
                String errorMsg = response != null ? response.getRetMsg() : "응답 없음";
                throw new CustomException(ExceptionMessage.SET_LEVERAGE_FAILED, errorMsg);
            }

            // 성공 시에만 캐시 (실패하면 다음 진입에서 재시도)
            leverageCache.put(cacheKey,
                    new LeverageSetting(request.getBuyLeverage(), request.getSellLeverage(), now));
            return response;
        } finally {
            UserApiKeyContext.clear();
        }
    }

    /**
     * Bybit에 손절/익절(Trading Stop)을 설정한다.
     * 사용자 API Key 컨텍스트를 설정한 후 Trading Stop API를 호출한다.
     *
     * @param request 손절/익절 설정 요청 (category, symbol, takeProfit, stopLoss, positionIdx)
     * @param userId  API Key를 조회할 사용자 ID
     * @return Trading Stop 설정 결과 응답
     * @throws CustomException 손절/익절 설정 실패 시 (SET_TRADING_STOP_FAILED)
     */
    public SetTradingStopResponse setTradingStop(SetTradingStopRequest request, Long userId) {
        setApiKeyContext(userId);
        try {
            SetTradingStopResponse response = positionClient.setTradingStop(request).getBody();

            // Bybit 응답 에러 코드 검증
            if (response == null || !"0".equals(response.getRetCode())) {
                String errorMsg = response != null ? response.getRetMsg() : "응답 없음";
                throw new CustomException(ExceptionMessage.SET_TRADING_STOP_FAILED, errorMsg);
            }

            return response;
        } finally {
            UserApiKeyContext.clear();
        }
    }

    /**
     * 주문의 WS 체결 대기를 등록한다. 주문 전송 전에 호출해야 한다. (체결 push가 주문 응답보다 먼저 올 수 있음)
     * 사용자의 private 스트림이 준비되지 않았으면 등록하지 않으며, 이후 체결 조회는 REST로만 수행된다.
     *
     * @param userId      사용자 ID
     * @param orderLinkId 주문에 지정할 orderLinkId
     * @return 등록했으면 true
     */
    public boolean expectExecution(Long userId, String orderLinkId) {
        return bybitExecutionWaiter.expect(userId, orderLinkId);
    }

    /**
     * 주문의 WS 체결 대기를 취소한다. (주문 실패 등) 등록되지 않았거나 이미 정리됐으면 무시한다.
     *
     * @param orderLinkId 취소할 orderLinkId
     */
    public void cancelExecution(String orderLinkId) {
        bybitExecutionWaiter.cancel(orderLinkId);
    }

    /**
     * 주문의 실체결 정보를 WS 우선으로 얻는다.
     * {@link #expectExecution}으로 등록한 주문이면 WS 전량 체결을 최대 {@value #WS_EXECUTION_WAIT_MS}ms 기다리고,
     * 미등록·시간 초과 시 REST 체결 조회({@link #getExecution(String, String, Long)})로 fallback한다.
     *
     * @param orderId     Bybit 주문 ID (REST fallback용)
     * @param orderLinkId 주문의 orderLinkId (WS 체결 매칭용)
     * @param symbol      심볼 (예: "BTCUSDT")
     * @param userId      사용자 ID
     * @return 집계된 실체결 정보 (execPrice: 수량 가중 평균가, execFee: 수수료 합, execQty: 수량 합),
     *         WS·REST 모두 실패 시 null
     */
    public GetExecutionListResponse.ExecutionInfo getExecution(String orderId, String orderLinkId,
                                                               String symbol, Long userId) {
        GetExecutionListResponse.ExecutionInfo wsExec = bybitExecutionWaiter.await(orderLinkId, WS_EXECUTION_WAIT_MS);
        // WS 전량 체결 수신 → REST 호출 생략
        if (wsExec != null) return wsExec;
        return getExecution(orderId, symbol, userId);
    }

    /**
     * Bybit에서 주문의 실체결 정보를 조회하여 집계한다.
     * 시장가 주문 1건이 여러 체결 레코드로 나뉠 수 있으므로 전 페이지를 수집해 합산한다.
     * 주문 직후 체결 내역이 미반영(또는 일부만 반영)될 수 있으므로 최대 2회 재시도한다.
     * 조회 실패 시 null을 반환하며, 호출부에서 추정값으로 fallback 처리해야 한다.
     *
     * @param orderId 조회할 Bybit 주문 ID
     * @param symbol  심볼 (예: "BTCUSDT")
     * @param userId  API Key를 조회할 사용자 ID
     * @return 집계된 실체결 정보 (execPrice: 수량 가중 평균가, execFee: 수수료 합, execQty: 수량 합),
     *         체결 레코드가 없으면 null
     */
    public GetExecutionListResponse.ExecutionInfo getExecution(String orderId, String symbol, Long userId) {
        setApiKeyContext(userId);
        try {
            GetExecutionListResponse.ExecutionInfo aggregated = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                if (attempt > 0) {
                    try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                }
                List<GetExecutionListResponse.ExecutionInfo> execs = fetchAllExecutions(orderId, symbol);
                // 체결 레코드 미반영 → 재시도
                if (execs.isEmpty()) continue;

                aggregated = aggregateExecutions(execs);
                // 체결 수량 합이 주문 수량 이상이면 전량 반영 완료 → 즉시 반환
                if (isFullyReflected(aggregated)) return aggregated;
            }

            if (aggregated != null) {
                // 재시도 후에도 일부만 반영 → 추정값보다 정확하므로 일부 합산값 반환
                log.warn("[체결 일부 반영] orderId={}, symbol={}, execQty={}, orderQty={} — 반영분 기준 사용",
                        orderId, symbol, aggregated.getExecQty(), aggregated.getOrderQty());
                return aggregated;
            }
            log.warn("[체결 조회 실패] orderId={}, symbol={} — 추정값 fallback 사용", orderId, symbol);
            return null;
        } finally {
            UserApiKeyContext.clear();
        }
    }

    /**
     * 주문의 체결 레코드를 nextPageCursor를 따라 전 페이지 수집한다.
     * 무한 루프 방지를 위해 최대 {@value #EXECUTION_MAX_PAGES} 페이지까지만 조회한다.
     *
     * @param orderId 조회할 Bybit 주문 ID
     * @param symbol  심볼 (예: "BTCUSDT")
     * @return 체결 레코드 목록 (조회 실패/미반영 시 빈 목록)
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

    /**
     * 체결 레코드들을 1건의 체결 정보로 집계한다.
     * execPrice = Σ(execPrice × execQty) / Σ execQty, execFee = Σ execFee, execQty = Σ execQty
     *
     * @param execs 동일 주문의 체결 레코드 목록 (1건 이상)
     * @return 집계된 체결 정보 (symbol/orderId/side/orderQty/execTime은 첫 레코드 기준)
     */
    private GetExecutionListResponse.ExecutionInfo aggregateExecutions(
            List<GetExecutionListResponse.ExecutionInfo> execs) {
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalValue = BigDecimal.ZERO;
        BigDecimal totalFee = BigDecimal.ZERO;
        for (GetExecutionListResponse.ExecutionInfo e : execs) {
            BigDecimal qty = new BigDecimal(e.getExecQty());
            totalQty = totalQty.add(qty);
            totalValue = totalValue.add(new BigDecimal(e.getExecPrice()).multiply(qty));
            totalFee = totalFee.add(new BigDecimal(e.getExecFee()));
        }

        GetExecutionListResponse.ExecutionInfo first = execs.get(0);
        // 수량 합이 0이면(비정상 데이터) 첫 레코드 가격 사용
        BigDecimal avgPrice = totalQty.signum() > 0
                ? totalValue.divide(totalQty, 8, RoundingMode.HALF_UP).stripTrailingZeros()
                : new BigDecimal(first.getExecPrice());

        GetExecutionListResponse.ExecutionInfo result = new GetExecutionListResponse.ExecutionInfo();
        result.setSymbol(first.getSymbol());
        result.setOrderId(first.getOrderId());
        result.setSide(first.getSide());
        result.setOrderQty(first.getOrderQty());
        result.setExecTime(first.getExecTime());
        result.setExecPrice(avgPrice.toPlainString());
        result.setExecQty(totalQty.stripTrailingZeros().toPlainString());
        result.setExecFee(totalFee.stripTrailingZeros().toPlainString());
        return result;
    }

    /**
     * 집계된 체결 수량이 원 주문 수량 이상인지(전량 반영) 판단한다.
     * orderQty가 없으면 비교 불가하므로 반영 완료로 간주한다.
     *
     * @param aggregated 집계된 체결 정보
     * @return 전량 반영 시 true
     */
    private boolean isFullyReflected(GetExecutionListResponse.ExecutionInfo aggregated) {
        if (aggregated.getOrderQty() == null || aggregated.getOrderQty().isBlank()) return true;
        return new BigDecimal(aggregated.getExecQty()).compareTo(new BigDecimal(aggregated.getOrderQty())) >= 0;
    }

    /**
     * Bybit에서 청산 주문 ID에 해당하는 포지션 손익 정보를 조회한다.
     * 같은 심볼의 다른 청산 건과 섞이지 않도록 orderId로 매칭하며,
     * 청산 직후 반영 지연 가능성을 고려해 최대 2회 재시도한다.
     * 응답에 펀딩비 필드가 없으므로 fundingFee는 손익 구성값으로 역산해 채운다.
     *
     * @param orderId 청산 주문 ID
     * @param symbol  심볼 (예: "BTCUSDT")
     * @param userId  API Key를 조회할 사용자 ID
     * @return 청산 손익 정보 (closedPnl, 역산 fundingFee 포함), 일치 건 없음/조회 실패 시 null
     */
    public GetClosedPnlResponse.ClosedPnlInfo getClosedPnl(String orderId, String symbol, Long userId) {
        setApiKeyContext(userId);
        try {
            // 최근 5분 이내 청산 건 조회 (orderId로 매칭하므로 범위를 넓혀도 혼입 없음)
            long startTime = System.currentTimeMillis() - CLOSED_PNL_LOOKBACK_MS;
            for (int attempt = 0; attempt < 2; attempt++) {
                if (attempt > 0) {
                    try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                }
                GetClosedPnlResponse body = positionClient
                        .getClosedPnl(Category.LINEAR.getCategory(), symbol, CLOSED_PNL_LIMIT, startTime)
                        .getBody();
                // 에러 응답 또는 빈 결과 → 재시도
                if (body == null || !"0".equals(body.getRetCode())
                        || body.getResult() == null || body.getResult().getList() == null) {
                    continue;
                }
                // 청산 주문 ID와 일치하는 건 탐색 (미반영이면 재시도)
                for (GetClosedPnlResponse.ClosedPnlInfo info : body.getResult().getList()) {
                    if (orderId.equals(info.getOrderId())) {
                        info.setFundingFee(calcFundingFee(info));
                        return info;
                    }
                }
            }
            log.warn("[손익 조회 실패] orderId={}, symbol={} — 계산식 fallback 사용", orderId, symbol);
            return null;
        } finally {
            UserApiKeyContext.clear();
        }
    }

    /**
     * 기준 시각 이후 심볼의 가장 최근 청산 건을 찾아 청산 방식을 판정한다. (놓친 외부 청산 재조정용)
     * closed-pnl에서 청산 주문 ID·평균 청산가를 얻고, 그 주문의 체결 내역(execType/stopOrderType/createType)으로 방식을 판정한다.
     * - 청산 건 없음: orderId null, closeType OTHER (방식 불명 → 호출부에서 큐 비활성화)
     * - 체결 내역 없음: orderId 유지, closeType OTHER
     *
     * @param userId      API Key를 조회할 사용자 ID
     * @param symbol      심볼 (예: "BTCUSDT")
     * @param sinceMillis 검색 시작 시각 (epoch ms, 보통 진입 시각 / Bybit 조회 범위 제한으로 최대 7일 전까지)
     * @return 청산 이벤트, closed-pnl 에러 응답·호출 실패 시 null (다음 재조정 주기에 재시도)
     */
    public PositionCloseDTO findLastClose(Long userId, String symbol, long sinceMillis) {
        setApiKeyContext(userId);
        try {
            // Bybit closed-pnl은 startTime~endTime 최대 7일 → 더 이전이면 7일 전으로 보정
            long startTime = Math.max(sinceMillis, System.currentTimeMillis() - CLOSED_PNL_MAX_RANGE_MS);
            GetClosedPnlResponse body = positionClient
                    .getClosedPnl(Category.LINEAR.getCategory(), symbol, CLOSED_PNL_LIMIT, startTime)
                    .getBody();
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
        } catch (Exception e) {
            log.warn("[청산 내역 조회 실패] symbol={}: {}", symbol, e.getMessage());
            return null;
        } finally {
            UserApiKeyContext.clear();
        }
    }

    /**
     * Bybit에서 심볼의 현재 포지션 수량을 조회한다. (외부 청산 확인용)
     * 단방향 모드는 심볼당 1건이며, 포지션이 없으면 size "0"으로 응답된다.
     *
     * @param userId API Key를 조회할 사용자 ID
     * @param symbol 심볼 (예: "BTCUSDT")
     * @return 포지션 수량 합계 (포지션 없으면 0), 에러 응답·호출 실패 시 null
     */
    public BigDecimal getPositionSize(Long userId, String symbol) {
        setApiKeyContext(userId);
        try {
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
        } catch (Exception e) {
            log.warn("[포지션 조회 실패] symbol={}: {}", symbol, e.getMessage());
            return null;
        } finally {
            UserApiKeyContext.clear();
        }
    }

    /**
     * 청산 손익 구성값으로 펀딩비를 역산한다. (Closed P&L = 매매차익 - 진입 수수료 - 청산 수수료 - 펀딩비)
     * 매매차익은 청산 주문 방향으로 판단한다. (Sell: 롱 청산 → 청산가치-진입가치, Buy: 숏 청산 → 진입가치-청산가치)
     *
     * @param info Bybit 청산 손익 정보
     * @return 펀딩비 (양수: 지급, 음수: 수취), 역산에 필요한 값이 없으면 null
     */
    private String calcFundingFee(GetClosedPnlResponse.ClosedPnlInfo info) {
        // 역산 필수 값 누락 시 계산 불가
        if (info.getCumEntryValue() == null || info.getCumExitValue() == null
                || info.getOpenFee() == null || info.getCloseFee() == null
                || info.getClosedPnl() == null || info.getSide() == null) {
            return null;
        }
        BigDecimal entryValue = new BigDecimal(info.getCumEntryValue());
        BigDecimal exitValue = new BigDecimal(info.getCumExitValue());
        // 청산 주문 방향 기준 매매차익
        BigDecimal grossPnl = "Sell".equalsIgnoreCase(info.getSide())
                ? exitValue.subtract(entryValue)
                : entryValue.subtract(exitValue);
        BigDecimal fundingFee = grossPnl
                .subtract(new BigDecimal(info.getOpenFee()))
                .subtract(new BigDecimal(info.getCloseFee()))
                .subtract(new BigDecimal(info.getClosedPnl()));
        return fundingFee.stripTrailingZeros().toPlainString();
    }

    /**
     * 사용자 거래소 API Key/Secret을 복호화하여 ThreadLocal 컨텍스트에 설정한다.
     * 현재 BYBIT 단일 거래소만 지원한다.
     *
     * @param userId 사용자 ID
     * @throws CustomException 거래소 API 키를 찾을 수 없는 경우 (API_KEY_NOT_FOUND)
     */
    private void setApiKeyContext(Long userId) {
        ExchangeKeyDTO key = exchangeKeyService.findKey(userId, ExchangeType.BYBIT);

        UserApiKeyContext.set(
                aesEncryptor.decrypt(key.getApiKey()),
                aesEncryptor.decrypt(key.getApiSecret())
        );
    }
}
