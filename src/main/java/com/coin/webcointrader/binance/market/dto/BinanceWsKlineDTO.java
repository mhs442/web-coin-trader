package com.coin.webcointrader.binance.market.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * Binance WebSocket {@code <symbol>@kline_<interval>} 메시지 매핑 DTO.
 * 봉 형성 중에도 수신되며, 봉 마감 여부는 k.x 로 판단한다.
 *
 * <p>메시지 예시: {@code {"e":"kline","E":123,"s":"BTCUSDT","k":{"t":..,"T":..,"i":"1m","o":"..","c":"..","h":"..","l":"..","v":"..","x":false}}}</p>
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceWsKlineDTO {

    @JsonProperty("s")
    private String symbol;  // 종목 심볼 (대문자, 예: BTCUSDT)
    @JsonProperty("k")
    private Kline kline;    // 봉 데이터

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Kline {
        @JsonProperty("t")
        private long startTime;   // 봉 시작 시각 (epoch ms)
        @JsonProperty("T")
        private long closeTime;   // 봉 종료 시각 (epoch ms)
        @JsonProperty("i")
        private String interval;  // 봉 간격 (예: "1m")
        @JsonProperty("o")
        private String open;      // 시가
        @JsonProperty("h")
        private String high;      // 고가
        @JsonProperty("l")
        private String low;       // 저가
        @JsonProperty("c")
        private String close;     // 종가
        @JsonProperty("v")
        private String volume;    // 거래량 (코인 기준)
        @JsonProperty("x")
        private boolean closed;   // 봉 마감 여부
    }
}
