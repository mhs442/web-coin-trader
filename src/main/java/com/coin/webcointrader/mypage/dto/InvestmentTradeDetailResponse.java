package com.coin.webcointrader.mypage.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 투자 행 클릭 시 표시되는 거래 상세 응답 DTO.
 * InvestmentHistory.patternStepId = TradeHistory.queueStepId 로 연결되어 조회된다.
 */
@Getter
@Builder
public class InvestmentTradeDetailResponse {
    private Long id;                // 거래 히스토리 ID
    private String orderType;       // ENTRY / SELL / LIQUIDATION
    private String executedPrice;   // 체결 가격
    private String amount;          // 주문 금액 (USDT)
    private String fee;             // 수수료 (null 가능)
    private String orderStatus;     // SUCCESS / FAILED
    private String errorMessage;    // 실패 사유 (null 가능)
    private String createdAt;       // 체결 일시
}
