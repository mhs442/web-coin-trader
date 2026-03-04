package com.coin.webcointrader.common.dto.request;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class SetLeverageRequest {
    private String category;        // 파생상품 카테고리 (예: "linear")
    private String symbol;          // 종목 심볼 (예: "BTCUSDT")
    private String buyLeverage;     // 매수(Long) 레버리지 배수
    private String sellLeverage;    // 매도(Short) 레버리지 배수
}
