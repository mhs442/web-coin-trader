package com.coin.webcointrader.mypage.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class InvestmentSummaryResponse {
    private String totalProfit;     // 전체 이익금 (profitLoss > 0 합산)
    private String totalLoss;       // 전체 손해금 (profitLoss < 0 합산)
    private String netTotal;        // 총액 (이익금 + 손해금)
    private long totalCount;        // 전체 투자 건수
    private long winCount;          // 익절 건수
    private String winRate;         // 승률 (%, 소수점 1자리)
}
