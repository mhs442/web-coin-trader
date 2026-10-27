package com.coin.webcointrader.common.autotrade.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/**
 * 패턴 큐 조회 응답.
 * 큐 + 평평한 패턴 목록(stepLevel → patternOrder 순)을 반환한다.
 */
@Getter
@Builder
public class PatternQueueResponse {
    private Long id;                        // 큐 ID
    private String symbol;                  // 코인 심볼
    private String exchangeType;            // 거래소 타입 (예: "BYBIT")
    private boolean active;                  // 활성화 여부
    private boolean cycle;                  // 단계 소진 후 1단계 재시작 여부
    private BigDecimal triggerRate;         // 트리거 기준 비율 (%)
    private List<PatternResponse> patterns; // 패턴 목록

    @Getter
    @Builder
    public static class PatternResponse {
        private Long id;                    // 패턴 ID
        private int stepLevel;              // 단계 레벨
        private int patternOrder;           // 단계 내 패턴 순서
        private BigDecimal amount;          // 투자 금액
        private int leverage;               // 레버리지
        private BigDecimal stopLossRate;    // 손절 비율
        private BigDecimal takeProfitRate;  // 익절 비율
        private List<String> conditionBlocks; // 조건 블록 side 목록 (순서대로)
        private String leafBlock;           // 실행(리프) 블록 side
    }
}
