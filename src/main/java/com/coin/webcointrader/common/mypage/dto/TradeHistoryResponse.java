package com.coin.webcointrader.common.mypage.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TradeHistoryResponse {
    private Long id;                // 거래 히스토리 PK
    private String symbol;          // 종목 심볼
    private String side;            // 매매 방향 (LONG / SHORT)
    private String amount;          // 주문 금액 (USDT)
    private String executedPrice;   // 체결 가격
    private String fee;             // 거래 수수료 (실수수료 또는 거래소 taker 수수료율 추정값, 기존 데이터는 null)
    private String orderType;       // 주문 유형 (entry / sell / liquidation / stop)
    private String orderStatus;     // 주문 결과 (SUCCESS / FAILED)
    private String errorMessage;    // 실패·중지 사유
    private String createdAt;       // 거래 일시 (yyyy-MM-dd HH:mm:ss)
}
