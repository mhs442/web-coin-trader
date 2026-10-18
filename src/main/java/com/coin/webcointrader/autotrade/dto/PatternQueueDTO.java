package com.coin.webcointrader.autotrade.dto;

import com.coin.webcointrader.common.enums.Side;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 패턴 큐 내부 전달용 DTO. (타 도메인이 엔티티 대신 사용)
 * 큐 → 단계 → 패턴 → 블록 계층 구조를 담는다.
 */
@Getter
@Builder
public class PatternQueueDTO {
    private final Long id;                      // 큐 ID
    private final String symbol;                // 코인 심볼
    private final String exchangeType;          // 거래소 타입 (예: "BYBIT")
    private final boolean active;               // 활성화 여부
    private final BigDecimal triggerRate;       // 트리거 기준 비율 (%)
    private final LocalDateTime createdAt;      // 등록 일시
    private final List<StepDTO> steps;          // 단계 목록

    @Getter
    @Builder
    public static class StepDTO {
        private final int stepLevel;                // 단계 레벨
        private final List<PatternDTO> patterns;    // 패턴 목록
    }

    @Getter
    @Builder
    public static class PatternDTO {
        private final int patternOrder;             // 패턴 순서
        private final BigDecimal amount;            // 투자 금액
        private final int leverage;                 // 레버리지
        private final BigDecimal stopLossRate;      // 손절 비율
        private final BigDecimal takeProfitRate;    // 익절 비율
        private final List<BlockDTO> blocks;        // 블록 목록
    }

    @Getter
    @Builder
    public static class BlockDTO {
        private final Side side;                    // LONG / SHORT
        private final int blockOrder;               // 블록 순서
        private final boolean leaf;                 // 리프 블록 여부
    }
}
