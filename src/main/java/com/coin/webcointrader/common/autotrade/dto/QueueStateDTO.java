package com.coin.webcointrader.common.autotrade.dto;

import com.coin.webcointrader.common.enums.Side;
import com.coin.webcointrader.common.enums.TradeOrderType;
import com.coin.webcointrader.common.trade.dto.TradeHistoryDTO;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
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
    private int currentStepLevel;      // 현재 단계 레벨 (1~20)
    private Long activePatternId;      // 현재 활성 패턴 ID
    private int currentBlockOrder;     // 현재 블록 순서

    // 블록 매칭 상태 (시간 기반 신호 판별용)
    private LocalDateTime blockBaseTime;   // 현재 블록 기준 시각 (60초 카운터 시작점)
    private String blockBasePrice;         // 현재 블록 기준가 (60초 후 비교 대상)

    // 포지션 상태
    private String entryPrice;         // 포지션 진입가 (MAIN: 실체결가, SIM: WebSocket 현재가)
    private String entryQty;           // 진입 시 코인 수량 (매도/청산 시 포지션 전량 청산에 재사용)
    private BigDecimal entryMargin;    // 진입 시 마진 (pattern.amount, applyProfitLoss 원금 기준)
    private BigDecimal entryFee;       // 진입 시 수수료 (MAIN: 실수수료, SIM/fallback: 추정값)
    private Long entryTime;            // 진입 주문 전송 시각 (epoch ms, 놓친 외부 청산 재조정 시 청산 내역 검색 시작점)
    private int closeSkipCount;        // 청산 수량 오류 연속 스킵 횟수 (5회 초과 시 큐 비활성화)
    private int entrySkipCount;        // 진입 시 최소 주문 단위 미달(qty=0) 연속 스킵 횟수 (5회 초과 시 큐 비활성화)
    private BigDecimal tpPrice;        // 익절가 (POSITION_HOLDING 매도 트리거 기준, 진입 시점 계산)
    private BigDecimal slPrice;        // 손절가 (POSITION_HOLDING 청산 트리거 기준, 진입 시점 계산)

    // 익절 지정가 (MAIN: 진입 확인 직후 TP가에 거는 reduceOnly 지정가 GTC, SIM은 null)
    private String tpOrderLinkId;      // 익절 지정가 사용자 지정 주문 ID (q{큐ID}-T-{ms}, null = 미등록)
    private String tpOrderId;          // 익절 지정가 거래소 주문 ID (null 가능)
    private BigDecimal tpOrderPrice;   // 익절 지정가 주문 가격 (tick 반올림 후, 부분 익절 손익 계산용)
    private int tpCancelFailCount;     // 손절 전 익절 지정가 취소 연속 실패 횟수 (5회면 큐 비활성화)

    // 거래소 완료 확인 대기 (진입: 포지션 보유 확인 / 청산: 포지션 0 확인 전까지 다음 동작 보류)
    private TradeOrderType pendingConfirmType; // 확인 대기 중인 주문 종류 (ENTRY/SELL/LIQUIDATION, null = 대기 없음)
    private TradeHistoryDTO pendingEntryHistory; // 진입 확인 대기 중 저장을 보류한 진입 이력 (확인 결과로 SUCCESS/FAILED 저장)
    private Side pendingCloseSignal;         // 손절 확인 후 다음 단계 진입 방향 (SELL이면 null)
    private int closeConfirmFailCount;       // 완료 미확인 연속 횟수 (5회 시 큐 비활성화)
    private long closeConfirmCheckedAt;      // 마지막 포지션 확인 시각 (epoch ms, 재확인 간격 제한용)
    private boolean stopRequested;           // 큐 종료(끄기·삭제) 청산 요청됨 — 청산 확인 시 다음 단계 대신 큐 비활성화
    private Double volumeBaseline;           // 거래량 급변 판정 기준 (진입 시점 24시간 거래량, null = 시세 미수신으로 미확정)

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

    /**
     * 현재 인스턴스를 초기 상태로 리셋한다.
     * 사이클 재시작 시 새 인스턴스 생성 없이 호출한다.
     */
    public void reset() {
        phase = TradePhase.TRIGGER_WAIT;
        basePrice = null;
        baseTime = null;
        direction = null;
        currentStepLevel = 1;
        activePatternId = null;
        currentBlockOrder = 1;
        blockBaseTime = null;
        blockBasePrice = null;
        entryPrice = null;
        entryQty = null;
        entryMargin = null;
        entryFee = null;
        entryTime = null;
        closeSkipCount = 0;
        entrySkipCount = 0;
        tpPrice = null;
        slPrice = null;
        tpOrderLinkId = null;
        tpOrderId = null;
        tpOrderPrice = null;
        tpCancelFailCount = 0;
        pendingConfirmType = null;
        pendingEntryHistory = null;
        pendingCloseSignal = null;
        closeConfirmFailCount = 0;
        closeConfirmCheckedAt = 0L;
        stopRequested = false;
        volumeBaseline = null;
    }
}
