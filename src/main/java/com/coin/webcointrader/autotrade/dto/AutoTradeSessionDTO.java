package com.coin.webcointrader.autotrade.dto;

import com.coin.webcointrader.autotrade.entity.PatternQueue;
import com.coin.webcointrader.bybit.client.market.dto.WebSocketKlineDTO;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.enums.TradeMode;
import com.coin.webcointrader.common.exchange.PositionCloseDTO;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 자동매매 세션의 인메모리 상태를 관리하는 DTO.
 * 활성화된 자동매매의 현재 진행 상황을 추적한다.
 */
@Getter
@Setter
public class AutoTradeSessionDTO {
    private Long userId;
    private String symbol;
    private ExchangeType exchangeType = ExchangeType.BYBIT;        // 거래소 타입 (기본값: BYBIT)
    private TradeMode tradeMode = TradeMode.MAIN;                  // 거래 모드 (MAIN: 실전, SIM: 모의)
    private List<PatternQueue> queues;                              // DB에서 로드한 패턴 큐 목록
    private Map<Long, QueueStateDTO> queueStates = new ConcurrentHashMap<>(); // 큐 ID → 런타임 상태
    private List<String> tradeLog = new CopyOnWriteArrayList<>();   // 매매 로그 (최대 50건, 레인 스레드·청산 후처리 스레드 동시 기록)
    private final AtomicReference<String> latestPrice = new AtomicReference<>(); // WS 최신 가격 슬롯 (처리 전 새 가격이 오면 덮어씀 → 밀린 틱 합치기)
    private final AtomicBoolean laneScheduled = new AtomicBoolean(false);        // 레인 작업 제출 여부 (세션당 동시에 1개만 실행)
    private final Queue<WebSocketKlineDTO.KlineData> pendingKlines = new ConcurrentLinkedQueue<>(); // 봉 마감 대기열 (봉당 1회 신호 → 덮어쓰지 않고 순서대로 모두 처리)
    private final Queue<PositionCloseDTO> pendingCloses = new ConcurrentLinkedQueue<>(); // 외부 청산 체결 대기열 (레인에서 가격·봉 마감보다 먼저 처리)

    /**
     * 로그를 추가한다. 50건 초과 시 가장 오래된 로그를 제거한다.
     */
    public synchronized void addLog(String message) {
        if (tradeLog.size() > 50) {
            tradeLog.remove(0);
        }
        tradeLog.add(message);
    }
}
