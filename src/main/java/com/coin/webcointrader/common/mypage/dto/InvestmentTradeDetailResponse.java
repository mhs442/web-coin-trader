package com.coin.webcointrader.common.mypage.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 투자 행 클릭 시 표시되는 거래 상세 응답 DTO.
 * InvestmentHistory.patternId = TradeHistory.patternId 로 연결되어 조회된다.
 */
@Getter
@Builder
public class InvestmentTradeDetailResponse {
    private Long id;                // 거래 히스토리 ID
    private String orderType;       // ENTRY / SELL / LIQUIDATION / STOP
    private String exchangeType;    // 거래소 타입 (예: BYBIT)
    private String executedPrice;   // 체결 가격
    private String amount;          // 주문 금액 (USDT)
    private String fee;             // 수수료 (null 가능)
    private String orderStatus;     // SUCCESS / FAILED
    private String errorMessage;    // 실패 사유 (null 가능)
    private String createdAt;       // 체결 일시
    private String profitLoss;      // 행별 실세 손익 (진입 수수료 비율 배분 포함, 성공한 EXIT 행만, 그 외 null)
    private String grossProfitLoss; // 행별 순수익 (수수료·펀딩비 차감 전 매매 차익, 성공한 EXIT 행만, 그 외 null)
}
