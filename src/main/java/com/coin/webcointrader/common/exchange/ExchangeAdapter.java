package com.coin.webcointrader.common.exchange;

import com.coin.webcointrader.common.client.market.dto.WebSocketKlineDTO;
import com.coin.webcointrader.common.dto.request.CreateOrderRequest;
import com.coin.webcointrader.common.dto.request.SetLeverageRequest;
import com.coin.webcointrader.common.dto.response.CreateOrderResponse;
import com.coin.webcointrader.common.dto.response.SetLeverageResponse;
import com.coin.webcointrader.common.dto.response.SetMarginModeResponse;
import com.coin.webcointrader.common.entity.TradeHistory;
import com.coin.webcointrader.common.enums.ExchangeType;

import java.math.BigDecimal;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * 거래소 어댑터 인터페이스.
 * 거래소별 구현체를 교체할 수 있도록 시세 조회, 주문, 계정 조회를 추상화한다.
 * WebSocket 콜백 등록 및 구독 관리도 포함한다.
 */
public interface ExchangeAdapter {

    /** 이 어댑터가 대상으로 하는 거래소 타입을 반환한다. */
    ExchangeType getExchangeType();

    // ─── WebSocket ───────────────────────────────────

    /**
     * 실시간 가격 수신 콜백을 등록한다.
     *
     * @param callback BiConsumer(심볼, 현재가 문자열)
     */
    void registerPriceCallback(BiConsumer<String, String> callback);

    /**
     * 1분봉 마감(confirm=true) 수신 콜백을 등록한다.
     *
     * @param callback BiConsumer(심볼, KlineData)
     */
    void registerKlineCallback(BiConsumer<String, WebSocketKlineDTO.KlineData> callback);

    /**
     * 활성 세션의 심볼 목록과 WebSocket 구독을 동기화한다.
     *
     * @param symbols 구독 유지할 심볼 집합
     */
    void syncSubscriptions(Set<String> symbols);

    /**
     * WebSocket이 최근 5초 이내에 데이터를 수신했으면 true를 반환한다.
     *
     * @return WebSocket 활성 상태 여부
     */
    boolean isWsActive();

    // ─── 시세 ─────────────────────────────────────────

    /**
     * 현재가를 문자열로 반환한다.
     *
     * @param symbol 코인 심볼 (예: "BTCUSDT")
     * @return 현재가 문자열, 데이터 없으면 null
     */
    String getCurrentPrice(String symbol);

    /**
     * USDT 금액을 코인 수량으로 변환한다. qtyStep 단위로 내림 처리한다.
     *
     * @param symbol       코인 심볼
     * @param usdt         USDT 금액
     * @param currentPrice 현재가
     * @return 수량 문자열, 변환 실패 시 null, 최소 단위 미달 시 "0"
     */
    String convertUsdtToQty(String symbol, BigDecimal usdt, BigDecimal currentPrice);

    // ─── 주문 ─────────────────────────────────────────

    /**
     * 시장가 주문을 실행한다.
     *
     * @param request 주문 요청 (symbol, side, qty 등)
     * @param userId  사용자 ID (API Key 조회용)
     * @param history 거래 이력 (실패 시 저장용, null 가능)
     * @return 주문 응답 (거래소 주문 ID 포함)
     */
    CreateOrderResponse placeOrder(CreateOrderRequest request, Long userId, TradeHistory history);

    /**
     * 레버리지를 설정한다.
     *
     * @param request 레버리지 설정 요청
     * @param userId  사용자 ID
     * @return 레버리지 설정 응답
     */
    SetLeverageResponse setLeverage(SetLeverageRequest request, Long userId);

    /**
     * 마진 모드를 Isolated로 전환한다.
     *
     * @param userId 사용자 ID
     * @return 마진 모드 설정 응답
     */
    SetMarginModeResponse switchToIsolated(Long userId);

    // ─── 계정 ─────────────────────────────────────────

    /**
     * 사용자의 지갑 잔고(총 자산)를 반환한다.
     *
     * @param userId 사용자 ID
     * @return 총 자산 (USDT)
     */
    BigDecimal getWalletBalance(Long userId);

    /**
     * API Key/Secret 유효성을 검증한다.
     *
     * @param apiKey    API Key
     * @param apiSecret API Secret
     * @return 유효하면 true
     */
    boolean validateApiKey(String apiKey, String apiSecret);
}
