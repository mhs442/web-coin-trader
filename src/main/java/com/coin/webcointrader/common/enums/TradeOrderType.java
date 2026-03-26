package com.coin.webcointrader.common.enums;

import lombok.Getter;

@Getter
public enum TradeOrderType {
    ENTRY("entry", "진입"),          // 포지션 진입
    SELL("sell", "매도"),           // 매도 (익절 성공)
    LIQUIDATION("liquidation", "청산");  // 청산 (손절)

    private final String tradeOrderType;
    private final String explain;

    TradeOrderType(String tradeOrderType, String explain) {
        this.tradeOrderType = tradeOrderType;
        this.explain = explain;
    }
}
