package com.coin.webcointrader.common.trade.dto;

import com.coin.webcointrader.common.trade.dto.OrderResultDTO;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 수동 주문 응답.
 */
@Getter
@AllArgsConstructor
public class PlaceOrderResponse {
    private String orderId;       // 거래소 주문 ID
    private String clientOrderId; // 사용자 지정 주문 ID (수동 주문은 null)

    public static PlaceOrderResponse from(OrderResultDTO result) {
        return new PlaceOrderResponse(result.getOrderId(), result.getClientOrderId());
    }
}
