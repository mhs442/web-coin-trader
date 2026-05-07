package com.coin.webcointrader.autotrade.dto;

import com.coin.webcointrader.common.entity.Side;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 큐별 자동매매 런타임 상태를 관리하는 DTO.
 * 각 PatternQueue마다 독립적으로 트리거/포지션/블록 매칭 상태를 추적한다.
 */
@Getter
@Setter
public class QueueStateDTO {
    private Long queueId;              // 큐 ID (PatternQueue.id 매핑)
    private TradePhase phase;          // 현재 페이즈

    // 트리거 대기 상태
    private String basePrice;          // 트리거 기준 가격
    private LocalDateTime baseTime;    // 트리거 기준 시각

    // 방향 및 진행 상태
    private Side direction;            // 트리거에서 결정된 방향 (LONG/SHORT)
    private Long activeStepId;         // 현재 활성 단계 ID
    private int currentStepLevel;      // 현재 단계 레벨 (1~20)
    private Long activePatternId;      // 현재 활성 패턴 ID
    private int currentBlockOrder;     // 현재 블록 순서

    // 블록 매칭 상태 (시간 기반 신호 판별용)
    private LocalDateTime blockBaseTime;   // 현재 블록 기준 시각 (60초 카운터 시작점)
    private String blockBasePrice;         // 현재 블록 기준가 (60초 후 비교 대상)

    // 포지션 상태
    private String entryPrice;         // 포지션 진입가
    private String entryQty;           // 진입 시 코인 수량 (매도/청산 시 포지션 전량 청산에 재사용)
    private BigDecimal entryMargin;    // 진입 시 마진 (pattern.amount, applyProfitLoss 원금 기준)
    private int closeSkipCount;        // 청산 수량 오류 연속 스킵 횟수 (5회 초과 시 큐 비활성화)
    private BigDecimal tpPrice;        // 익절가 (POSITION_HOLDING 매도 트리거 기준, 진입 시점 계산)
    private BigDecimal slPrice;        // 손절가 (POSITION_HOLDING 청산 트리거 기준, 진입 시점 계산)

    // 처리 중 플래그 (중복 주문 방지)
    private final AtomicBoolean processing = new AtomicBoolean(false);

    /**
     * 처리 락을 시도한다. 이미 처리 중이면 false를 반환한다.
     *
     * @return 락 획득 성공 시 true, 이미 처리 중이면 false
     */
    public boolean tryLock() {
        return processing.compareAndSet(false, true);
    }

    /**
     * 처리 락을 해제한다.
     */
    public void unlock() {
        processing.set(false);
    }

    /**
     * 초기 상태로 생성한다. (TRIGGER_WAIT 페이즈)
     *
     * @param queueId 큐 ID
     * @return 초기 상태의 QueueStateDTO
     */
    public static QueueStateDTO initial(Long queueId) {
        QueueStateDTO state = new QueueStateDTO();
        state.queueId = queueId;
        state.phase = TradePhase.TRIGGER_WAIT;
        state.currentStepLevel = 1;
        state.currentBlockOrder = 1;
        return state;
    }
}
