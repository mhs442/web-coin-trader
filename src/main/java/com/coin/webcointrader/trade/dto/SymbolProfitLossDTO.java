package com.coin.webcointrader.trade.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/** 심볼별 손익 합계. */
@Getter
@AllArgsConstructor
public class SymbolProfitLossDTO {
    private final String symbol;            // 코인 심볼
    private final BigDecimal profitLoss;    // 해당 심볼 손익 합
}
