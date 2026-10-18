package com.coin.webcointrader.trade.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/** 기간 내 손익 합계 (수익 합 / 손실 합). */
@Getter
@AllArgsConstructor
public class ProfitLossSummaryDTO {
    private final BigDecimal totalProfit;   // 수익 거래 손익 합 (0 이상)
    private final BigDecimal totalLoss;     // 손실 거래 손익 합 (0 이하)
}
