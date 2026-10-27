package com.coin.webcointrader.binance.market.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Binance GET /fapi/v1/depth 응답. 각 호가는 [가격, 잔량] 문자열 배열이다.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceDepthResponse {

    private Long lastUpdateId;     // 호가 갱신 ID
    @JsonProperty("T")
    private Long transactionTime;  // 호가 기준 체결 시각 (epoch ms)
    private List<String[]> bids;   // 매수 호가 [가격, 잔량]
    private List<String[]> asks;   // 매도 호가 [가격, 잔량]
}
