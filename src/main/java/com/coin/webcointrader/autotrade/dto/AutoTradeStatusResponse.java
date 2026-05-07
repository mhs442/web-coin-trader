package com.coin.webcointrader.autotrade.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * 자동매매 상태 조회 응답.
 */
@Getter
@Builder
public class AutoTradeStatusResponse {
    private boolean active;         // 활성화 여부
    private Long currentQueueId;    // 현재 실행 중인 큐 ID
    private int currentStepLevel;   // 현재 진행 중인 단계 레벨
    private String phase;           // 현재 페이즈 (TRIGGER_WAIT, BLOCK_MATCHING, POSITION_HOLDING)
    private String direction;       // 현재 방향 (LONG, SHORT, null)
    private int currentBlockOrder;  // 현재 블록 순서
    private int activePatternOrder; // 현재 활성 패턴 순서 (1 or 2)
    private long elapsedSeconds;         // 트리거 경과 시간(초)
    private long blockRemainingSeconds;  // 현재 블록 잔여 대기 시간(초) (BLOCK_MATCHING 상태)
    private BigDecimal changeRate;       // 트리거 변동률(%)
    private BigDecimal amount;           // 투입 금액 (USDT)
    private String entryPrice;           // 진입 시점 체결가 (POSITION_HOLDING 시 설정)
    private BigDecimal tpPrice;          // 익절가 (POSITION_HOLDING 시 설정)
    private BigDecimal slPrice;          // 손절가 (POSITION_HOLDING 시 설정)
}
