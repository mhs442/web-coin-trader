package com.coin.webcointrader.binance.trade.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * Binance POST /fapi/v1/order 요청 파라미터. (쿼리 문자열로 전송, null 필드는 생략)
 */
@Getter
@Builder
public class BinanceOrderRequest {
    private String symbol;           // 종목 심볼 (예: BTCUSDT)
    private String side;             // 주문 방향 (BUY/SELL)
    private String type;             // 주문 유형 (MARKET)
    private String quantity;         // 주문 수량 (코인 기준)
    private String reduceOnly;       // 포지션 축소 전용 ("true", 청산 주문만 지정)
    private String newClientOrderId; // 사용자 지정 주문 ID (null이면 거래소 자동 생성)
}
