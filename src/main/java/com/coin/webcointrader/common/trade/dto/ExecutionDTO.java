package com.coin.webcointrader.common.trade.dto;

import com.coin.webcointrader.common.enums.OrderSide;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * 거래소 공통 체결 정보.
 * 단건 체결 레코드이거나, 주문 1건의 체결을 집계한 값이다. (집계 시 execPrice는 수량 가중 평균가)
 */
@Getter
@SuperBuilder
@NoArgsConstructor
public class ExecutionDTO {
    private String symbol;    // 종목 심볼 (예: BTCUSDT)
    private String orderId;   // 거래소 주문 ID
    private OrderSide side;   // 주문 방향
    private String execPrice; // 체결가 (집계 시 수량 가중 평균가)
    private String execQty;   // 체결 수량 (집계 시 합계)
    private String execFee;   // 체결 수수료 (집계 시 합계)
    private String orderQty;  // 원 주문 수량 (전량 반영 판단용, null 가능)
    private String execTime;  // 체결 시각 (epoch ms 문자열)
}
