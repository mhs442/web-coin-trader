package com.coin.webcointrader.bybit.market.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Bybit WebSocket 공개 체결({@code publicTrade.{symbol}}) 메시지 매핑 DTO.
 * 한 메시지에 여러 체결이 묶여 올 수 있다.
 *
 * <p>메시지 예시:
 * {@code {"topic":"publicTrade.BTCUSDT","type":"snapshot","ts":..,"data":[{"T":..,"s":"BTCUSDT","S":"Buy","v":"0.001","p":"16578.50","i":"..","BT":false}]}}</p>
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class WebSocketPublicTradeDTO {

    private String topic;           // 토픽 (예: publicTrade.BTCUSDT)
    private List<TradeData> data;   // 체결 목록

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TradeData {
        @JsonProperty("T")
        private long tradeTime;     // 체결 시각 (epoch ms)
        @JsonProperty("s")
        private String symbol;      // 종목 심볼
        @JsonProperty("v")
        private String qty;         // 체결 수량 (코인 기준)
    }
}
