package com.coin.webcointrader.autotrade.dto;

import com.coin.webcointrader.common.entity.Side;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

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

    // 포지션 상태
    private String entryPrice;         // 포지션 진입가

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
