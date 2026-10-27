package com.coin.webcointrader.common.trade.dto;

import com.coin.webcointrader.common.enums.OrderSide;
import com.coin.webcointrader.common.trade.dto.OrderRequestDTO;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 수동 시장가 주문 요청. (거래소 중립)
 */
@Getter
@Setter
@NoArgsConstructor
public class PlaceOrderRequest {
    private String symbol;  // 종목 심볼 (예: "BTCUSDT")
    private OrderSide side; // 주문 방향 (BUY / SELL)
    private String qty;     // 주문 수량 (코인 단위, 예: "0.01")

    /** 공통 주문 요청으로 변환한다. */
    public OrderRequestDTO toOrderRequest() {
        return OrderRequestDTO.builder().symbol(symbol).side(side).qty(qty).build();
    }
}
