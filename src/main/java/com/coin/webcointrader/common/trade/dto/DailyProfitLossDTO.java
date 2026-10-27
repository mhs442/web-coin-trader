package com.coin.webcointrader.common.trade.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/** 일별 손익 합계. */
@Getter
@AllArgsConstructor
public class DailyProfitLossDTO {
    private final String date;              // 날짜 (yyyy-MM-dd)
    private final BigDecimal profitLoss;    // 해당 일자 손익 합
}
