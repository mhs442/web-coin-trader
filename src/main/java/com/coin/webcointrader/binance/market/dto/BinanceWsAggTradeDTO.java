package com.coin.webcointrader.binance.market.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * Binance WebSocket {@code <symbol>@aggTrade} (집계 체결) 메시지 매핑 DTO.
 * 같은 가격·같은 테이커 주문의 체결을 묶어 1건으로 보낸다.
 *
 * <p>메시지 예시: {@code {"e":"aggTrade","E":123,"s":"BTCUSDT","a":5933014,"p":"0.001","q":"100","f":100,"l":105,"T":123,"m":true}}</p>
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceWsAggTradeDTO {

    @JsonProperty("s")
    private String symbol;      // 종목 심볼 (대문자, 예: BTCUSDT)
    @JsonProperty("q")
    private String qty;         // 체결 수량 (코인 기준)
    @JsonProperty("T")
    private long tradeTime;     // 체결 시각 (epoch ms)
}
