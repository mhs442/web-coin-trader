package com.coin.webcointrader.binance.market.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * Binance WebSocket {@code <symbol>@ticker} (24시간 티커) 메시지 매핑 DTO.
 * 매번 전체 값이 오므로 병합 없이 교체한다. 필드명이 한 글자(대소문자 구분)라 명시적으로 매핑한다.
 *
 * <p>메시지 예시: {@code {"e":"24hrTicker","E":123,"s":"BTCUSDT","P":"1.23","c":"0.0025","h":"..","l":"..","v":"..","q":".."}}</p>
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceWsTickerDTO {

    @JsonProperty("s")
    private String symbol;              // 종목 심볼 (대문자, 예: BTCUSDT)
    @JsonProperty("c")
    private String lastPrice;           // 현재가
    @JsonProperty("P")
    private String priceChangePercent;  // 24시간 변동률 (퍼센트, 예: "1.23" = 1.23%)
    @JsonProperty("h")
    private String highPrice;           // 24시간 최고가
    @JsonProperty("l")
    private String lowPrice;            // 24시간 최저가
    @JsonProperty("v")
    private String volume;              // 24시간 거래량 (코인 기준)
    @JsonProperty("q")
    private String quoteVolume;         // 24시간 거래대금 (USDT 기준)
}
