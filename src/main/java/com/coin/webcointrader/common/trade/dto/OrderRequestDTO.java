package com.coin.webcointrader.common.trade.dto;

import com.coin.webcointrader.common.enums.OrderSide;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * 거래소 공통 주문 요청. (USDT 무기한, price 없으면 시장가 / 있으면 지정가 GTC)
 * 거래소별 표기(예: Bybit "Buy"/"Sell", category "linear")는 각 구현체가 변환한다.
 */
@Getter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
public class OrderRequestDTO {
    private String symbol;          // 종목 심볼 (예: BTCUSDT)
    private OrderSide side;         // 주문 방향 (BUY/SELL)
    private String qty;             // 주문 수량 (코인 기준, qtyStep 단위)
    private String price;           // 지정가 주문 가격 (null이면 시장가, tickSize 단위)
    private boolean reduceOnly;     // 포지션 축소 전용 여부 (청산 주문 true)
    private String clientOrderId;   // 사용자 지정 주문 ID (결과 불명확 시 조회·WS 체결 매칭 키, null 가능)
    private String takeProfit;      // 거래소 익절 트리거 가격 (null이면 미설정)
    private String stopLoss;        // 거래소 손절 트리거 가격 (null이면 미설정)
}
