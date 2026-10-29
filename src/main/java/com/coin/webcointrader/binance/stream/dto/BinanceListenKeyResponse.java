package com.coin.webcointrader.binance.stream.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

/**
 * Binance POST·PUT /fapi/v1/listenKey 응답.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceListenKeyResponse {

    private String listenKey;  // user data stream 접속 키 (유효기간 60분, 계정당 1개)
}
