package com.coin.webcointrader.common.enums;

/**
 * 주문 방향 (매수/매도).
 * 포지션 방향({@link Side})과 달리 주문 자체의 방향이다. (예: 롱 청산 주문 = SELL)
 */
public enum OrderSide {
    BUY, SELL
}
