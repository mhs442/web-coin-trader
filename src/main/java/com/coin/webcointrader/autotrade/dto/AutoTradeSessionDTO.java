package com.coin.webcointrader.autotrade.dto;

import com.coin.webcointrader.common.entity.PatternQueue;
import com.coin.webcointrader.common.enums.TradeMode;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 자동매매 세션의 인메모리 상태를 관리하는 DTO.
 * 활성화된 자동매매의 현재 진행 상황을 추적한다.
 */
@Getter
@Setter
public class AutoTradeSessionDTO {
    private Long userId;
    private String symbol;
    private TradeMode tradeMode = TradeMode.MAIN;                  // 거래 모드 (MAIN: 실전, SIM: 모의)
    private List<PatternQueue> queues;                              // DB에서 로드한 패턴 큐 목록
    private Map<Long, QueueStateDTO> queueStates = new HashMap<>(); // 큐 ID → 런타임 상태
    private List<String> tradeLog = new ArrayList<>();              // 매매 로그 (최대 50건)

    /**
     * 로그를 추가한다. 50건 초과 시 가장 오래된 로그를 제거한다.
     */
    public void addLog(String message) {
        if (tradeLog.size() > 50) {
            tradeLog.remove(0);
        }
        tradeLog.add(message);
    }
}
