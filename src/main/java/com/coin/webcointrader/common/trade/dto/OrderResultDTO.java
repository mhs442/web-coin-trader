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
}
