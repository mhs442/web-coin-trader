package com.coin.webcointrader.bybit.client;

import com.coin.webcointrader.bybit.client.account.AccountClient;
import com.coin.webcointrader.bybit.client.market.BybitWebSocketClient;
import com.coin.webcointrader.bybit.client.market.dto.WebSocketKlineDTO;
import com.coin.webcointrader.bybit.client.market.dto.WebSocketTickerDTO;
import com.coin.webcointrader.bybit.dto.request.CreateOrderRequest;
import com.coin.webcointrader.bybit.dto.request.SetLeverageRequest;
import com.coin.webcointrader.bybit.dto.response.CreateOrderResponse;
import com.coin.webcointrader.bybit.dto.response.GetWalletBalanceResponse;
import com.coin.webcointrader.bybit.dto.response.SetLeverageResponse;
import com.coin.webcointrader.bybit.dto.response.SetMarginModeResponse;
import com.coin.webcointrader.trade.dto.TradeHistoryDTO;
import com.coin.webcointrader.login.dto.ExchangeKeyDTO;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.exchange.ExchangeClient;
import com.coin.webcointrader.common.exchange.PrivateStreamCapable;
import com.coin.webcointrader.common.enums.LogMessage;
import com.coin.webcointrader.bybit.client.privatews.BybitPrivateWebSocketClient;
import com.coin.webcointrader.bybit.client.privatews.dto.WebSocketExecutionDTO;
import com.coin.webcointrader.bybit.util.BybitCloseTypeResolver;
import com.coin.webcointrader.common.exchange.PositionCloseDTO;
import com.coin.webcointrader.login.service.ExchangeKeyService;
import com.coin.webcointrader.common.util.AesEncryptor;
import com.coin.webcointrader.common.util.UserApiKeyContext;
import com.coin.webcointrader.login.service.BybitApiKeyValidator;
import com.coin.webcointrader.market.service.MarketService;
import com.coin.webcointrader.trade.service.TradeService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Bybit 거래소 어댑터.
 * {@link ExchangeClient} 인터페이스의 Bybit 구현체.
 * WebSocket 콜백 팬아웃, 주문 실행, 시세 조회, 계정 조회를 제공한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BybitExchangeClient implements ExchangeClient, PrivateStreamCapable {

    private final BybitWebSocketClient bybitWebSocketClient;
    private final MarketService marketService;
    private final TradeService tradeService;
    private final AccountClient accountClient;
    private final ExchangeKeyService exchangeKeyService;
    private final AesEncryptor aesEncryptor;
    private final BybitApiKeyValidator bybitApiKeyValidator;
    private final BybitPrivateWebSocketClient bybitPrivateWebSocketClient;

    // 가격 변동 구독자 목록 (BiConsumer<심볼, 현재가>)
    private final List<BiConsumer<String, String>> priceCallbacks = new CopyOnWriteArrayList<>();

    // 1분봉 마감 구독자 목록 (BiConsumer<심볼, KlineData>)
    private final List<BiConsumer<String, WebSocketKlineDTO.KlineData>> klineCallbacks = new CopyOnWriteArrayList<>();

    // 포지션 청산 체결 구독자 목록
    private final List<Consumer<PositionCloseDTO>> positionCloseListeners = new CopyOnWriteArrayList<>();

    // 포지션을 줄일 수 있는 체결 유형 (Trade: 일반, BustTrade: 강제청산, AdlTrade: ADL / Funding 등은 제외)
    private static final Set<String> CLOSE_EXEC_TYPES = Set.of("Trade", "BustTrade", "AdlTrade");

    /**
     * 빈 초기화 시 BybitWebSocketClient에 ticker/kline 수신 콜백을, private WS 클라이언트에 체결 콜백을 등록한다.
     * 등록된 콜백이 priceCallbacks/klineCallbacks/positionCloseListeners로 팬아웃하여 AutoTradeService까지 전달된다.
     */
    @PostConstruct
    public void init() {
        bybitWebSocketClient.addTickerCallback(this::onTickerReceived);
        bybitWebSocketClient.addKlineCallback(this::onKlineReceived);
        bybitPrivateWebSocketClient.addExecutionCallback(this::onExecutionReceived);
    }

    @Override
    public ExchangeType getExchangeType() {
        return ExchangeType.BYBIT;
    }

    // ─── WebSocket 콜백 관리 ─────────────────────────

    @Override
    public void registerPriceCallback(BiConsumer<String, String> callback) {
        priceCallbacks.add(callback);
    }

    @Override
    public void registerKlineCallback(BiConsumer<String, WebSocketKlineDTO.KlineData> callback) {
        klineCallbacks.add(callback);
    }

    @Override
    public void syncSubscriptions(Set<String> symbols) {
        bybitWebSocketClient.syncSubscriptions(symbols);
    }

    @Override
    public void addPositionCloseListener(Consumer<PositionCloseDTO> listener) {
        positionCloseListeners.add(listener);
    }

    /**
     * private 스트림(체결 수신) 연결 대상 사용자와 실제 연결을 동기화한다.
     * 신규 대상은 API Key를 복호화해 연결을 열고, 대상에서 빠진 사용자는 연결을 닫는다.
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

    @Override
    public boolean isWsActive() {
        return marketService.isWsActive();
    }

    // ─── 시세 ─────────────────────────────────────────

    @Override
    public String getCurrentPrice(String symbol) {
        com.coin.webcointrader.bybit.dto.response.FindTickerResponse.TickerInfo ticker =
                marketService.getWsTicker(symbol);
        return ticker != null ? ticker.getLastPrice() : null;
    }

    @Override
    public String convertUsdtToQty(String symbol, BigDecimal usdt, BigDecimal currentPrice) {
        return marketService.convertUsdtToQty(symbol, usdt, currentPrice);
    }

    // ─── 주문 ─────────────────────────────────────────

    @Override
    public CreateOrderResponse placeOrder(CreateOrderRequest request, Long userId, TradeHistoryDTO history) {
        return tradeService.placeOrder(request, userId, history);
    }

    @Override
    public SetLeverageResponse setLeverage(SetLeverageRequest request, Long userId) {
        return tradeService.setLeverage(request, userId);
    }

    @Override
    public SetMarginModeResponse switchToIsolated(Long userId) {
        return tradeService.switchToIsolated(userId);
    }

    // ─── 계정 ─────────────────────────────────────────

    @Override
    public BigDecimal getWalletBalance(Long userId) {
        setApiKeyContext(userId);
        try {
            GetWalletBalanceResponse response = accountClient.getWalletBalance("UNIFIED").getBody();
            if (response == null || response.getResult() == null
                    || response.getResult().getList() == null
                    || response.getResult().getList().isEmpty()) {
                return BigDecimal.ZERO;
            }
            // 첫 번째 계좌(UNIFIED)의 totalEquity 반환
            String equity = response.getResult().getList().get(0).getTotalEquity();
            return equity != null ? new BigDecimal(equity) : BigDecimal.ZERO;
        } finally {
            UserApiKeyContext.clear();
        }
    }

    @Override
    public boolean validateApiKey(String apiKey, String apiSecret) {
        return bybitApiKeyValidator.validate(apiKey, apiSecret);
    }

    // ─── 내부 헬퍼 ─────────────────────────────────────

    /**
     * BybitWebSocketClient에서 ticker 메시지를 수신하면 호출된다.
     * 심볼과 현재가를 추출하여 등록된 priceCallbacks에 팬아웃한다.
     */
    private void onTickerReceived(WebSocketTickerDTO dto) {
        if (dto == null || dto.getData() == null) return;
        WebSocketTickerDTO.TickerData data = dto.getData();
        if (data.getSymbol() == null || data.getLastPrice() == null) return;

        String symbol = data.getSymbol();
        String price = data.getLastPrice();

        for (BiConsumer<String, String> callback : priceCallbacks) {
            try {
                callback.accept(symbol, price);
            } catch (Exception e) {
                log.error("[BybitExchangeClient] 가격 콜백 오류: symbol={}, error={}", symbol, e.getMessage());
            }
        }
    }

    /**
     * private WS에서 체결을 수신하면 호출된다. (WebSocket 수신 스레드)
     * 포지션을 줄인 체결(closedSize > 0) 중 주문의 마지막 체결만 청산 이벤트로 바꿔 positionCloseListeners에 팬아웃한다.
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

        PositionCloseDTO event = PositionCloseDTO.builder()
                .userId(userId)
                .exchangeType(ExchangeType.BYBIT)
                .symbol(data.getSymbol())
                .orderId(data.getOrderId())
                .orderLinkId(data.getOrderLinkId())
                .execPrice(data.getExecPrice())
                .closeType(BybitCloseTypeResolver.resolve(
                        data.getExecType(), data.getStopOrderType(), data.getCreateType()))
                .build();
        for (Consumer<PositionCloseDTO> listener : positionCloseListeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                log.error("[BybitExchangeClient] 청산 체결 콜백 오류: symbol={}, orderId={}, error={}",
                        event.getSymbol(), event.getOrderId(), e.getMessage());
            }
        }
    }

    /** 숫자 문자열이 0보다 큰지 판단한다. (null·빈 값·파싱 불가는 false) */
    private boolean isPositive(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            return new BigDecimal(value).signum() > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * BybitWebSocketClient에서 kline 메시지를 수신하면 호출된다.
     * confirm=true인 봉만 필터링하여 등록된 klineCallbacks에 팬아웃한다.
     */
    private void onKlineReceived(WebSocketKlineDTO dto) {
        if (dto == null || dto.getData() == null || dto.getTopic() == null) return;

        // 토픽에서 심볼 추출 (예: "kline.1.BTCUSDT" → "BTCUSDT")
        String topic = dto.getTopic();
        int lastDot = topic.lastIndexOf('.');
        if (lastDot < 0 || lastDot == topic.length() - 1) return;
        String symbol = topic.substring(lastDot + 1);

        for (WebSocketKlineDTO.KlineData kline : dto.getData()) {
            // 봉 마감(confirm=true)인 경우에만 콜백 호출
            if (!Boolean.TRUE.equals(kline.getConfirm())) continue;

            for (BiConsumer<String, WebSocketKlineDTO.KlineData> callback : klineCallbacks) {
                try {
                    callback.accept(symbol, kline);
                } catch (Exception e) {
                    log.error("[BybitExchangeClient] kline 콜백 오류: symbol={}, error={}", symbol, e.getMessage());
                }
            }
        }
    }

    /**
     * 사용자의 Bybit API Key/Secret을 복호화하여 ThreadLocal 컨텍스트에 설정한다.
     *
     * @param userId 사용자 ID
     */
    private void setApiKeyContext(Long userId) {
        ExchangeKeyDTO key = exchangeKeyService.findKey(userId, ExchangeType.BYBIT);

        UserApiKeyContext.set(
                aesEncryptor.decrypt(key.getApiKey()),
                aesEncryptor.decrypt(key.getApiSecret())
        );
    }
}
