package com.coin.webcointrader.binance.market.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

/**
 * Binance GET /fapi/v2/ticker/price 응답 항목. (symbol 생략 시 전체 종목 배열)
 *
 * <p>응답 예시: {@code [{"symbol":"BTCUSDT","price":"6000.01","time":1589437530011}]}</p>
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinancePriceTickerResponse {

    private String symbol;  // 종목 심볼 (예: BTCUSDT)
    private String price;   // 최근 체결가
    private Long time;      // 기준 시각 (epoch ms)
}
