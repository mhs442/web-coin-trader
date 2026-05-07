package com.coin.webcointrader.mypage.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class InvestmentHistoryResponse {
    private Long id;                // 투자 히스토리 PK
    private String symbol;          // 종목 심볼
    private String side;            // 매매 방향 (LONG / SHORT)
    private String entryPrice;      // 진입가
    private String exitPrice;       // 청산가
    private String amount;          // 투입 금액 (마진, 레버리지 미포함)
    private int leverage;           // 레버리지
    private String tpPrice;         // 익절 예상가
    private String slPrice;         // 손절 예상가
    private String profitLoss;      // 손익금
    private String createdAt;       // 거래 일시 (yyyy-MM-dd HH:mm:ss)
}
