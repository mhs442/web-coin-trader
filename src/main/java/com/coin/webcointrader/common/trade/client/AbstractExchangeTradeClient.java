package com.coin.webcointrader.common.trade.client;

import com.coin.webcointrader.common.trade.dto.PositionCloseDTO;
import com.coin.webcointrader.common.trade.dto.ClosedPnlDTO;
import com.coin.webcointrader.common.trade.dto.ClosedPnlQueryDTO;
import com.coin.webcointrader.common.trade.dto.ExecutionDTO;
import com.coin.webcointrader.common.trade.dto.OrderLookupDTO;
import com.coin.webcointrader.common.trade.dto.OrderRequestDTO;
import com.coin.webcointrader.common.trade.dto.OrderResultDTO;
import com.coin.webcointrader.common.util.AesEncryptor;
import com.coin.webcointrader.common.login.dto.ExchangeKeyDTO;
import com.coin.webcointrader.common.login.service.ExchangeKeyService;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 거래소 거래 클라이언트 공통 처리.
 * 사용자 API 키 조회·복호화·바인딩, 마진 모드/레버리지 설정 캐시, 결과 불명확 주문 복구,
 * 체결 집계·재조회, 청산 손익 매칭·펀딩비 역산, 청산 리스너 팬아웃을 담당한다.
 * 하위 클래스는 거래소 API 호출(protected abstract 메서드)과 스트림 관련 public 메서드를 구현한다.
 */
@Slf4j
public abstract class AbstractExchangeTradeClient implements ExchangeTradeClient {

    // 마진 모드/레버리지 설정 캐시 유효 시간 (ms, 앱 밖에서 변경한 경우 최대 이 시간 내 재설정)
    protected static final long SETTING_CACHE_TTL_MS = 10 * 60 * 1000L;
    // 결과 불명확 주문의 clientOrderId 조회 횟수 (거래소 반영 지연 대비)
    protected static final int ORDER_LOOKUP_ATTEMPTS = 2;
    // 조회 재시도 대기 시간 (ms)
    protected static final long RETRY_DELAY_MS = 500L;
    // 체결·손익 조회 횟수 (주문 직후 미반영 대비)
    protected static final int FETCH_ATTEMPTS = 2;
    // 스트림 체결 최대 대기 시간 (ms) — 초과 시 REST 조회로 fallback
    protected static final long WS_EXECUTION_WAIT_MS = 2000L;

    protected final ExchangeKeyService exchangeKeyService;
    protected final AesEncryptor aesEncryptor;
    protected final Clock clock;

    // 격리 마진 전환 완료 시각 캐시 (key: userId:symbol, value: 설정 시각 ms)
    private final Map<String, Long> isolatedCache = new ConcurrentHashMap<>();
    // 레버리지 설정 캐시 (key: userId:symbol)
    private final Map<String, LeverageSetting> leverageCache = new ConcurrentHashMap<>();
    // 포지션 청산 리스너
    private final List<Consumer<PositionCloseDTO>> positionCloseListeners = new CopyOnWriteArrayList<>();

    /**
     * 마지막으로 설정 완료한 레버리지.
     */
    private record LeverageSetting(
            String leverage, // 레버리지 (롱·숏 동일)
            long setAt       // 설정 시각 (ms)
    ) {}

    protected AbstractExchangeTradeClient(ExchangeKeyService exchangeKeyService, AesEncryptor aesEncryptor, Clock clock) {
        this.exchangeKeyService = exchangeKeyService;
        this.aesEncryptor = aesEncryptor;
        this.clock = clock;
    }

    // ─── 하위 클래스 구현: API 키 ───

    /**
     * 복호화된 API 키를 이후 거래소 호출에 쓰이도록 바인딩한다. (예: ThreadLocal 설정)
     *
     * @param apiKey    평문 API Key
     * @param apiSecret 평문 API Secret
     */
    protected abstract void bindApiKey(String apiKey, String apiSecret);

    /** {@link #bindApiKey}로 바인딩한 API 키를 해제한다. */
    protected abstract void unbindApiKey();

    // ─── 하위 클래스 구현: 거래소 호출 (API 키 바인딩 상태에서 호출됨) ───

    /**
     * 거래소에 주문을 전송한다.
     *
     * @param request 주문 요청
     * @return 주문 접수 결과
     * @throws com.coin.webcointrader.common.exception.CustomException 거래소가 주문을 거부한 경우 (ORDER_FAILED)
     */
    protected abstract OrderResultDTO submitOrder(OrderRequestDTO request);

    /**
     * 주문 전송 예외가 결과 불명확(타임아웃·5xx 등, 거래소가 이미 접수했을 수 있음)인지 판단한다.
     *
     * @param e 주문 전송 예외
     * @return 결과 불명확이면 true
     */
    protected abstract boolean isAmbiguousFailure(RuntimeException e);

    /**
     * clientOrderId로 주문 상태를 조회한다.
     *
     * @param request 원 주문 요청 (clientOrderId 존재 보장)
     * @return 주문 상태, 아직 조회되지 않으면 null
     */
    protected abstract OrderLookupDTO lookupOrder(OrderRequestDTO request);

    /**
     * 마진 모드를 격리로 전환한다. 이미 격리 모드면 정상 반환한다.
     *
     * @param symbol 종목 심볼 (계정 단위로 설정하는 거래소는 사용하지 않음)
     * @throws com.coin.webcointrader.common.exception.CustomException 전환 실패 시 (SWITCH_MARGIN_MODE_FAILED)
     */
    protected abstract void doSwitchToIsolated(String symbol);

    /**
     * 심볼 레버리지를 설정한다. 이미 같은 값이면 정상 반환한다.
     *
     * @param symbol   종목 심볼
     * @param leverage 레버리지 (롱·숏 동일)
     * @throws com.coin.webcointrader.common.exception.CustomException 설정 실패 시 (SET_LEVERAGE_FAILED)
     */
    protected abstract void doSetLeverage(String symbol, String leverage);

    /**
     * 주문의 체결 레코드를 전부 조회한다.
     *
     * @param orderId 거래소 주문 ID
     * @param symbol  종목 심볼
     * @return 체결 레코드 목록 (미반영·조회 실패 시 빈 목록)
     */
    protected abstract List<ExecutionDTO> fetchExecutions(String orderId, String symbol);

    /**
     * 청산 주문의 청산 손익을 조회한다. (조회 방식·펀딩비 산출은 거래소별로 다름)
     *
     * @param query 조회 조건 (청산 주문 ID·심볼·진입 시각·진입 수수료)
     * @return 청산 손익, 에러 응답이거나 아직 반영 전이면 null (재조회 대상)
     */
    protected abstract ClosedPnlDTO fetchClosedPnl(ClosedPnlQueryDTO query);

    // ─── 하위 클래스 구현: 스트림 (API 키 불필요) ───

    /**
     * {@link #expectExecution}으로 등록한 주문의 전량 체결을 스트림에서 기다린다.
     *
     * @param clientOrderId 사용자 지정 주문 ID
     * @param waitMillis    최대 대기 시간 (ms)
     * @return 집계된 체결 정보, 미등록·시간 초과 시 null
     */
    protected abstract ExecutionDTO awaitExecution(String clientOrderId, long waitMillis);

    // ─── 공통 처리 ───

    /**
     * 사용자의 이 거래소 API 키를 복호화·바인딩한 상태로 작업을 실행하고, 끝나면 해제한다.
     *
     * @param userId 사용자 ID
     * @param action 실행할 작업
     * @return 작업 결과
     * @throws com.coin.webcointrader.common.exception.CustomException 키를 찾을 수 없는 경우 (API_KEY_NOT_FOUND)
     */
    protected <T> T withApiKey(Long userId, Supplier<T> action) {
        ExchangeKeyDTO key = exchangeKeyService.findKey(userId, getExchangeType());
        bindApiKey(aesEncryptor.decrypt(key.getApiKey()), aesEncryptor.decrypt(key.getApiSecret()));
        try {
            return action.get();
        } finally {
            unbindApiKey();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public OrderResultDTO placeOrder(OrderRequestDTO request, Long userId) {
        return withApiKey(userId, () -> {
            try {
                return submitOrder(request);
            } catch (RuntimeException e) {
                // 결과 불명확 → clientOrderId로 실제 접수 여부 확인 (거부 확정 실패는 그대로 전파)
                OrderResultDTO recovered = isAmbiguousFailure(e) ? recoverByClientOrderId(request) : null;
                if (recovered == null) throw e;
                return recovered;
            }
        });
    }

    /**
     * clientOrderId로 주문 상태를 최대 {@value #ORDER_LOOKUP_ATTEMPTS}회 조회해 접수 여부를 확인한다.
     *
     * @param request 원 주문 요청
     * @return 접수·체결 확인 시 복구 결과, 미확인·거부·clientOrderId 없음이면 null
     */
    private OrderResultDTO recoverByClientOrderId(OrderRequestDTO request) {
        String clientOrderId = request.getClientOrderId();
        // clientOrderId 없는 주문(수동 주문)은 조회 불가
        if (clientOrderId == null) return null;

        for (int attempt = 0; attempt < ORDER_LOOKUP_ATTEMPTS; attempt++) {
            if (attempt > 0) sleep(RETRY_DELAY_MS);
            try {
                OrderLookupDTO order = lookupOrder(request);
                // 미반영 → 재조회
                if (order == null) continue;
                // 거부·체결 없이 취소 → 실패 확정
                if (!order.isAccepted()) {
                    log.warn("[{} 주문 결과 확인] 주문 미체결 확정 clientOrderId={}", getExchangeType(), clientOrderId);
                    return null;
                }
                log.warn("[{} 주문 결과 확인] 응답 수신 실패했으나 주문 접수 확인 clientOrderId={}, orderId={}",
                        getExchangeType(), clientOrderId, order.getOrderId());
                return OrderResultDTO.builder().orderId(order.getOrderId()).clientOrderId(clientOrderId).build();
            } catch (Exception e) {
                // 조회 자체 실패 → 재조회 (최종 실패 시 원래 예외로 처리)
                log.warn("[{} 주문 결과 확인] 조회 실패 clientOrderId={}: {}", getExchangeType(), clientOrderId, e.getMessage());
            }
        }
        log.warn("[{} 주문 결과 확인] 주문을 찾을 수 없음 clientOrderId={} — 실패로 처리", getExchangeType(), clientOrderId);
        return null;
    }

    /**
     * {@inheritDoc}
     * 전환 성공 후 {@value #SETTING_CACHE_TTL_MS}ms 동안은 거래소 호출을 생략한다.
     */
    @Override
    public void switchToIsolated(Long userId, String symbol) {
        long now = clock.millis();
        String cacheKey = userId + ":" + symbol;
        // 유효 시간 내 전환 이력 있음 → 호출 생략 (진입 지연·호출 한도 절감)
        Long setAt = isolatedCache.get(cacheKey);
        if (setAt != null && now - setAt <= SETTING_CACHE_TTL_MS) return;

        withApiKey(userId, () -> {
            doSwitchToIsolated(symbol);
            return null;
        });
        // 성공 시에만 캐시 (실패하면 다음 진입에서 재시도)
        isolatedCache.put(cacheKey, now);
    }

    /**
     * {@inheritDoc}
     * 같은 사용자·심볼에 같은 값을 설정한 지 {@value #SETTING_CACHE_TTL_MS}ms 이내면 거래소 호출을 생략한다.
     */
    @Override
    public void setLeverage(Long userId, String symbol, String leverage) {
        long now = clock.millis();
        String cacheKey = userId + ":" + symbol;
        // 유효 시간 내 같은 값 설정 이력 있음 → 호출 생략 (값이 다르면 재설정)
        LeverageSetting cached = leverageCache.get(cacheKey);
        if (cached != null && now - cached.setAt() <= SETTING_CACHE_TTL_MS && cached.leverage().equals(leverage)) return;

        withApiKey(userId, () -> {
            doSetLeverage(symbol, leverage);
            return null;
        });
        // 성공 시에만 캐시
        leverageCache.put(cacheKey, new LeverageSetting(leverage, now));
    }

    /**
     * {@inheritDoc}
     * 스트림 체결을 최대 {@value #WS_EXECUTION_WAIT_MS}ms 기다리고, 없으면 REST로 최대 {@value #FETCH_ATTEMPTS}회 조회한다.
     */
    @Override
    public ExecutionDTO getExecution(String orderId, String clientOrderId, String symbol, Long userId) {
        ExecutionDTO streamed = awaitExecution(clientOrderId, WS_EXECUTION_WAIT_MS);
        // 스트림 전량 체결 수신 → REST 호출 생략
        if (streamed != null) return streamed;
        return withApiKey(userId, () -> fetchAggregatedExecution(orderId, symbol));
    }

    /**
     * REST로 체결 레코드를 조회·집계한다. 일부만 반영되면 재조회하고, 끝내 일부면 반영분을 반환한다.
     *
     * @param orderId 거래소 주문 ID
     * @param symbol  종목 심볼
     * @return 집계된 체결 정보, 레코드가 없으면 null
     */
    private ExecutionDTO fetchAggregatedExecution(String orderId, String symbol) {
        ExecutionDTO aggregated = null;
        for (int attempt = 0; attempt < FETCH_ATTEMPTS; attempt++) {
            if (attempt > 0) sleep(RETRY_DELAY_MS);
            List<ExecutionDTO> execs = fetchExecutions(orderId, symbol);
            // 체결 레코드 미반영 → 재시도
            if (execs == null || execs.isEmpty()) continue;

            aggregated = aggregateExecutions(execs);
            // 체결 수량 합이 주문 수량 이상 → 전량 반영 완료
            if (isFullyReflected(aggregated)) return aggregated;
        }

        if (aggregated != null) {
            log.warn("[{} 체결 일부 반영] orderId={}, execQty={}, orderQty={} — 반영분 기준 사용",
                    getExchangeType(), orderId, aggregated.getExecQty(), aggregated.getOrderQty());
        } else {
            log.warn("[{} 체결 조회 실패] orderId={}, symbol={} — 추정값 fallback 사용", getExchangeType(), orderId, symbol);
        }
        return aggregated;
    }

    /**
     * 같은 주문의 체결 레코드들을 1건으로 집계한다.
     * execPrice = Σ(execPrice × execQty) / Σ execQty, execFee = Σ execFee, execQty = Σ execQty
     *
     * @param execs 같은 주문의 체결 레코드 (1건 이상)
     * @return 집계 결과 (symbol/orderId/side/orderQty/execTime은 첫 레코드 기준)
     */
    protected static ExecutionDTO aggregateExecutions(List<ExecutionDTO> execs) {
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalValue = BigDecimal.ZERO;
        BigDecimal totalFee = BigDecimal.ZERO;
        for (ExecutionDTO e : execs) {
            BigDecimal qty = new BigDecimal(e.getExecQty());
            totalQty = totalQty.add(qty);
            totalValue = totalValue.add(new BigDecimal(e.getExecPrice()).multiply(qty));
            totalFee = totalFee.add(new BigDecimal(e.getExecFee()));
        }

        ExecutionDTO first = execs.get(0);
        // 수량 합이 0이면(비정상 데이터) 첫 레코드 가격 사용
        BigDecimal avgPrice = totalQty.signum() > 0
                ? totalValue.divide(totalQty, 8, RoundingMode.HALF_UP).stripTrailingZeros()
                : new BigDecimal(first.getExecPrice());

        return ExecutionDTO.builder()
                .symbol(first.getSymbol())
                .orderId(first.getOrderId())
                .side(first.getSide())
                .orderQty(first.getOrderQty())
                .execTime(first.getExecTime())
                .execPrice(avgPrice.toPlainString())
                .execQty(totalQty.stripTrailingZeros().toPlainString())
                .execFee(totalFee.stripTrailingZeros().toPlainString())
                .build();
    }

    /**
     * 집계된 체결 수량이 원 주문 수량 이상인지 판단한다. orderQty가 없으면 반영 완료로 간주한다.
     *
     * @param aggregated 집계된 체결 정보
     * @return 전량 반영 시 true
     */
    private static boolean isFullyReflected(ExecutionDTO aggregated) {
        if (aggregated.getOrderQty() == null || aggregated.getOrderQty().isBlank()) return true;
        return new BigDecimal(aggregated.getExecQty()).compareTo(new BigDecimal(aggregated.getOrderQty())) >= 0;
    }

    /**
     * {@inheritDoc}
     * 거래소 반영 지연 대비 최대 {@value #FETCH_ATTEMPTS}회 조회한다.
     */
    @Override
    public ClosedPnlDTO getClosedPnl(ClosedPnlQueryDTO query, Long userId) {
        return withApiKey(userId, () -> {
            for (int attempt = 0; attempt < FETCH_ATTEMPTS; attempt++) {
                if (attempt > 0) sleep(RETRY_DELAY_MS);
                ClosedPnlDTO info = fetchClosedPnl(query);
                // 에러 응답 또는 미반영(null) → 재시도
                if (info != null) return info;
            }
            log.warn("[{} 손익 조회 실패] orderId={}, symbol={} — 계산식 fallback 사용", getExchangeType(),
                    query.getOrderId(), query.getSymbol());
            return null;
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addPositionCloseListener(Consumer<PositionCloseDTO> listener) {
        positionCloseListeners.add(listener);
    }

    /**
     * 감지한 포지션 청산을 리스너에 전달한다. 한 리스너의 예외가 다른 리스너 전달을 막지 않는다.
     *
     * @param event 청산 이벤트
     */
    protected void publishPositionClose(PositionCloseDTO event) {
        for (Consumer<PositionCloseDTO> listener : positionCloseListeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                log.error("[{} 청산 리스너 오류] orderId={}: {}", getExchangeType(), event.getOrderId(), e.getMessage());
            }
        }
    }

    /**
     * 재시도 전 대기한다. (테스트에서 대기 없이 실행하도록 재정의 가능)
     *
     * @param millis 대기 시간 (ms)
     */
    protected void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
