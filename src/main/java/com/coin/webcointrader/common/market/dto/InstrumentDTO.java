package com.coin.webcointrader.common.market.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * 거래소 공통 종목 거래 규칙.
 */
@Getter
@SuperBuilder
@NoArgsConstructor
public class InstrumentDTO {
    private String symbol;       // 종목 심볼 (예: BTCUSDT)
    private String qtyStep;      // 주문 수량 단위 (예: "0.001")
    private String tickSize;     // 최소 가격 단위 (예: "0.10")
    private String minOrderQty;  // 최소 주문 수량
}
