package com.coin.webcointrader.autotrade.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/**
 * 패턴 큐 조회 응답.
 * 큐 → 단계 → 패턴 → 블록 계층 구조를 반환한다.
 */
@Getter
@Builder
public class PatternQueueResponse {
    private Long id;                        // 큐 ID
    private String symbol;                  // 코인 심볼
    private boolean active;                  // 활성화 여부
    private Integer triggerSeconds;         // 트리거 기준 시간 (초)
    private BigDecimal triggerRate;         // 트리거 기준 비율 (%)
    private List<StepResponse> steps;       // 단계 목록

    @Getter
    @Builder
    public static class StepResponse {
        private Long id;                    // 단계 ID
        private int stepLevel;              // 단계 레벨
        private List<PatternResponse> patterns;  // 패턴 목록
    }

    @Getter
    @Builder
    public static class PatternResponse {
        private Long id;                    // 패턴 ID
        private int patternOrder;           // 패턴 순서
        private BigDecimal amount;          // 투자 금액
        private int leverage;               // 레버리지
        private BigDecimal stopLossRate;    // 손절 비율
        private BigDecimal takeProfitRate;  // 익절 비율
        private List<BlockResponse> blocks; // 블록 목록
    }

    @Getter
    @Builder
    public static class BlockResponse {
        private Long id;                    // 블록 ID
        private String side;                // LONG / SHORT
        private int blockOrder;             // 블록 순서
        private boolean isLeaf;             // 리프 블록 여부
    }
}
