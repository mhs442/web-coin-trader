package com.coin.webcointrader.common.mypage.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class InvestmentSummaryResponse {
    private String totalProfit;     // 전체 이익금 (profitLoss > 0 합산)
    private String totalLoss;       // 전체 손해금 (profitLoss < 0 합산)
    private String netTotal;        // 총액 (이익금 + 손해금)
    private String totalAmount;     // 총 투자금 (투입 금액(마진) 합, 필터 전체 기준)
    private long totalCount;        // 전체 투자 건수
    private long winCount;          // 익절 건수
    private String winRate;         // 승률 (%, 소수점 1자리)
}
