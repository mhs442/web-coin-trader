package com.coin.webcointrader.autotrade.dto;

import com.coin.webcointrader.common.entity.Queue;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 자동매매 세션의 인메모리 상태를 관리하는 DTO.
 * 활성화된 자동매매의 현재 진행 상황을 추적한다.
 */
@Getter
@Setter
public class AutoTradeSessionDTO {
    private Long userId;
    private String symbol;
    private List<Queue> queues;               // DB에서 로드한 큐 목록
    private int currentQueueIndex;            // 현재 실행 중인 큐 인덱스
    private int currentStepIndex;             // 현재 큐 내 진행 단계
    private String previousPrice;             // 직전 가격 (신호 판별용)
    private List<String> tradeLog = new ArrayList<>();  // 매매 로그 (최대 50건)

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
