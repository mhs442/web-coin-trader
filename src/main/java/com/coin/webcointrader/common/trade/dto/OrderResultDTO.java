package com.coin.webcointrader.common.trade.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * 거래소 공통 주문 접수 결과.
 */
@Getter
@SuperBuilder
@NoArgsConstructor
public class OrderResultDTO {
    private String orderId;        // 거래소 주문 ID
    private String clientOrderId;  // 사용자 지정 주문 ID
    private boolean protectionFailed; // 진입 뒤 별도로 거는 거래소 보호 주문(TP/SL) 접수 실패 여부
    private boolean protectionCancelled; // 이 주문 접수로 거래소 보호 주문(TP/SL)을 취소했는지 (청산 주문)
}
