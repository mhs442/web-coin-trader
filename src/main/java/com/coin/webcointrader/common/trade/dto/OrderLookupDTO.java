package com.coin.webcointrader.common.trade.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * 사용자 지정 주문 ID로 조회한 주문 상태. (결과 불명확 주문 복구용)
 */
@Getter
@SuperBuilder
@NoArgsConstructor
public class OrderLookupDTO {
    private String orderId;   // 거래소 주문 ID
    private boolean accepted; // 접수·체결 여부 (false: 거부·체결 없이 취소)
}
