package com.coin.webcointrader.autotrade.dto;

/**
 * 자동매매 큐의 실행 페이즈.
 */
public enum TradePhase {
    TRIGGER_WAIT,      // 트리거 대기 (큐 활성화 직후, 방향 판단 전)
    POSITION_OPEN,     // (deprecated) 호환용 - processQueue에서 BLOCK_MATCHING으로 즉시 전환
    BLOCK_MATCHING,    // 블록 매칭 진행 중 (조건 블록 60초 신호 → 리프 도달 시 즉시 진입)
    POSITION_HOLDING   // 포지션 보유 중 (실시간 가격 vs TP/SL 가격 비교 → 매도/청산)
}
