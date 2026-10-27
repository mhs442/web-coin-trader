package com.coin.webcointrader.binance.trade.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

/**
 * Binance POST·GET /fapi/v1/order 응답. (주문 상태 판단·청산 방식 판정에 필요한 필드만 매핑)
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceOrderResponse {
    private String orderId;       // 거래소 주문 ID (숫자 → 문자열)
    private String symbol;        // 종목 심볼
    private String status;        // 주문 상태 (NEW, PARTIALLY_FILLED, FILLED, CANCELED, EXPIRED …)
    private String clientOrderId; // 사용자 지정 주문 ID (강제청산 autoclose-, ADL adl_autoclose)
    private String origQty;       // 원 주문 수량
    private String executedQty;   // 체결 수량
    private String avgPrice;      // 평균 체결가
    private String side;          // 주문 방향 (BUY/SELL)
    private String type;          // 현재 주문 유형
    private String origType;      // 원 주문 유형 (TAKE_PROFIT_MARKET, STOP_MARKET, LIQUIDATION …)
}
