package com.coin.webcointrader.bybit;

import com.coin.webcointrader.common.client.account.AccountClient;
import com.coin.webcointrader.common.client.market.BybitWebSocketClient;
import com.coin.webcointrader.common.client.market.dto.WebSocketKlineDTO;
import com.coin.webcointrader.common.client.market.dto.WebSocketTickerDTO;
import com.coin.webcointrader.common.dto.request.CreateOrderRequest;
import com.coin.webcointrader.common.dto.request.SetLeverageRequest;
import com.coin.webcointrader.common.dto.response.CreateOrderResponse;
import com.coin.webcointrader.common.dto.response.GetWalletBalanceResponse;
import com.coin.webcointrader.common.dto.response.SetLeverageResponse;
import com.coin.webcointrader.common.dto.response.SetMarginModeResponse;
import com.coin.webcointrader.common.entity.TradeHistory;
import com.coin.webcointrader.common.entity.UserExchangeKey;
import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.exchange.ExchangeAdapter;
import com.coin.webcointrader.common.repository.UserExchangeKeyRepository;
import com.coin.webcointrader.common.util.AesEncryptor;
import com.coin.webcointrader.common.util.UserApiKeyContext;
import com.coin.webcointrader.login.service.BybitApiKeyValidator;
import com.coin.webcointrader.market.service.MarketService;
import com.coin.webcointrader.trade.service.TradeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Bybit 거래소 어댑터.
 * {@link ExchangeAdapter} 인터페이스의 Bybit 구현체.
 * WebSocket 콜백 팬아웃, 주문 실행, 시세 조회, 계정 조회를 제공한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BybitExchangeAdapter implements ExchangeAdapter {

    private final BybitWebSocketClient bybitWebSocketClient;
    private final MarketService marketService;
    private final TradeService tradeService;
    private final AccountClient accountClient;
    private final UserExchangeKeyRepository userExchangeKeyRepository;
    private final AesEncryptor aesEncryptor;
    private final BybitApiKeyValidator bybitApiKeyValidator;

    // 가격 변동 구독자 목록 (BiConsumer<심볼, 현재가>)
    private final List<BiConsumer<String, String>> priceCallbacks = new CopyOnWriteArrayList<>();

    // 1분봉 마감 구독자 목록 (BiConsumer<심볼, KlineData>)
    private final List<BiConsumer<String, WebSocketKlineDTO.KlineData>> klineCallbacks = new CopyOnWriteArrayList<>();

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
    public boolean isWsActive() {
        return marketService.isWsActive();
    }

    // ─── 시세 ─────────────────────────────────────────

    @Override
    public String getCurrentPrice(String symbol) {
        com.coin.webcointrader.common.dto.response.FindTickerResponse.TickerInfo ticker =
                marketService.getWsTicker(symbol);
        return ticker != null ? ticker.getLastPrice() : null;
    }

    @Override
    public String convertUsdtToQty(String symbol, BigDecimal usdt, BigDecimal currentPrice) {
        return marketService.convertUsdtToQty(symbol, usdt, currentPrice);
    }

    // ─── 주문 ─────────────────────────────────────────

    @Override
    public CreateOrderResponse placeOrder(CreateOrderRequest request, Long userId, TradeHistory history) {
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
                log.error("[BybitExchangeAdapter] 가격 콜백 오류: symbol={}, error={}", symbol, e.getMessage());
            }
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
                    log.error("[BybitExchangeAdapter] kline 콜백 오류: symbol={}, error={}", symbol, e.getMessage());
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
        UserExchangeKey key = userExchangeKeyRepository
                .findByUserIdAndExchangeType(userId, ExchangeType.BYBIT)
                .orElseThrow(() -> new CustomException(ExceptionMessage.API_KEY_NOT_FOUND));

        UserApiKeyContext.set(
                aesEncryptor.decrypt(key.getApiKey()),
                aesEncryptor.decrypt(key.getApiSecret())
        );
    }
}
