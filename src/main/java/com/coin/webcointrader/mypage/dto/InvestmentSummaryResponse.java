package com.coin.webcointrader.mypage.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class InvestmentSummaryResponse {
    private String totalProfit;     // 전체 이익금 (profitLoss > 0 합산)
    private String totalLoss;       // 전체 손해금 (profitLoss < 0 합산)
    private String netTotal;        // 총액 (이익금 + 손해금)
}
