package com.coin.webcointrader.common.enums;

import lombok.Getter;

@Getter
public enum TradeOrderType {
    ENTRY("entry", "진입"),          // 포지션 진입
    SELL("sell", "익절"),           // 익절 종료 (저장 값은 기존 "sell" 유지)
    LIQUIDATION("liquidation", "청산"),  // 청산 (손절)
    STOP("stop", "중지");             // 큐 중지 기록 (주문 없음, 사유는 error_message)

    private final String tradeOrderType;
    private final String explain;

    TradeOrderType(String tradeOrderType, String explain) {
        this.tradeOrderType = tradeOrderType;
        this.explain = explain;
    }
}
