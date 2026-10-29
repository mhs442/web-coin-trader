package com.coin.webcointrader.common.enums;

/**
 * 주문 취소 결과.
 */
public enum OrderCancelResult {
    CANCELLED,  // 취소 성공 (주문이 호가창에서 내려감)
    NOT_FOUND,  // 주문 없음 — 이미 전부 체결됐거나 취소됨 (Bybit 110001, Binance -2011)
    FAILED      // 그 외 실패 (네트워크·키 없음 등, 주문 상태 불명)
}
