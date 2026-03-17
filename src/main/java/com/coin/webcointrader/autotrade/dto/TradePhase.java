package com.coin.webcointrader.autotrade.dto;

/**
 * 자동매매 큐의 실행 페이즈.
 */
public enum TradePhase {
    TRIGGER_WAIT,    // 트리거 대기 (큐 활성화 직후, 방향 판단 전)
    POSITION_OPEN,   // 포지션 진입 대기 (레버리지 설정 → 시장가 주문)
    BLOCK_MATCHING   // 블록 매칭 진행 중 (조건 블록 순차 매칭 → 리프 도달 시 매도)
}
