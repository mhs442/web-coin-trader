package com.coin.webcointrader.binance.trade.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

/**
 * Binance GET /fapi/v1/income 응답 항목. (손익 내역 1건)
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceIncomeResponse {
    private String symbol;     // 종목 심볼
    private String incomeType; // 내역 유형 (FUNDING_FEE …)
    private String income;     // 금액 (양수: 수취, 음수: 지급)
    private String asset;      // 자산 (USDT)
    private Long time;         // 발생 시각 (epoch ms)
}
