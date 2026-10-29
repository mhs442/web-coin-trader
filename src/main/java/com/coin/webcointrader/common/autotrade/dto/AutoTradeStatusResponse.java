package com.coin.webcointrader.common.autotrade.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

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
    private Double entryVolume;          // 진입 시점 24시간 거래량 (오토스탑 큐 POSITION_HOLDING 시 설정, null = 미확정)
    private boolean tpOrderPending;      // 익절 지정가가 거래소에 걸려 체결 대기 중인지 (MAIN 전용, leaf 스피너 표시용)
    private List<Long> holdingQueueIds;  // 포지션 보유 중인 큐 ID 목록 (끄기·삭제 시 청산 확인 팝업 판단용)
}
