package com.coin.webcointrader.common.enums;

/**
 * 지원 거래소 타입.
 * 거래소별 어댑터 라우팅 및 세션 키 구성에 사용된다.
 */
public enum ExchangeType {
    BYBIT   // Bybit 거래소 (현재 유일한 구현체)
    // 향후 추가 예정: BINANCE, OKX 등
}
