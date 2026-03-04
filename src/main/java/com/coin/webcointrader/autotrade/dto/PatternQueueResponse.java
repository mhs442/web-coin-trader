package com.coin.webcointrader.autotrade.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 패턴 큐 조회 응답.
 */
@Getter
@Builder
public class PatternQueueResponse {
    private Long id;                   // 큐 ID
    private Integer sortOrder;         // 실행 우선순위
    private String useYn;              // 활성화 여부 (Y/N)
    private List<StepResponse> steps;  // 단계 목록

    @Getter
    @Builder
    public static class StepResponse {
        private Long id;               // 단계 ID
        private Integer stepOrder;     // 순서
        private String side;           // LONG / SHORT
        private String quantity;       // 수량
    }
}
