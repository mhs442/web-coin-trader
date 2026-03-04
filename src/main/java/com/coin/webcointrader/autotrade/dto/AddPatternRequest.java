package com.coin.webcointrader.autotrade.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 패턴 추가 요청.
 */
@Getter
@Setter
public class AddPatternRequest {
    private String symbol;              // 코인 심볼 (예: BTCUSDT)
    private List<StepRequest> steps;    // 단계 목록

    @Getter
    @Setter
    public static class StepRequest {
        private String side;            // LONG / SHORT
        private String quantity;        // 수량
    }
}
