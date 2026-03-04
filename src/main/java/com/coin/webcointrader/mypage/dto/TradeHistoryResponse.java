package com.coin.webcointrader.mypage.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TradeHistoryResponse {
    private Long id;                // 거래 히스토리 PK
    private String symbol;          // 종목 심볼
    private String side;            // 매매 방향 (LONG / SHORT)
    private String quantity;        // 주문 수량
    private String executedPrice;   // 체결 가격
    private String orderStatus;     // 주문 결과 (SUCCESS / FAILED)
    private String errorMessage;    // 오류 메시지 (FAILED 시)
    private String createdAt;       // 거래 일시 (yyyy-MM-dd HH:mm:ss)
}
