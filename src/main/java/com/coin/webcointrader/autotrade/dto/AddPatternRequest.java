package com.coin.webcointrader.autotrade.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * 자동매매 큐 등록 요청.
 * 프론트에서 구성한 큐(트리거 + 단계 + 패턴 + 블록) 데이터를 받는다.
 */
@Getter @Setter
public class AddPatternRequest {
    private String symbol;              // 코인 심볼 (예: BTCUSDT)
    private String tradeMode;          // 거래 모드 ("MAIN" 또는 "SIM")
    private BigDecimal triggerRate;     // 트리거 기준 상승/하락률 (%)
    private List<StepRequest> steps;    // 단계 목록

    @Getter @Setter
    public static class StepRequest {
        private Integer stepOrder;              // 단계 순서 (1부터 시작)
        private List<PatternRequest> patterns;  // 패턴 목록 (최대 2개)
    }

    @Getter @Setter
    public static class PatternRequest {
        private BigDecimal amount;          // 투자 금액 (USDT)
        private Integer leverage;           // 레버리지 배수
        private BigDecimal stopLossRate;    // 손절 비율 (%, null이면 미설정)
        private BigDecimal takeProfitRate;  // 익절 비율 (%, null이면 미설정)
        private List<BlockRequest> conditionBlocks;  // 조건 블록 (최대 5개)
        private BlockRequest leafBlock;              // 실행(리프) 블록 (1개)
    }

    @Getter @Setter
    public static class BlockRequest {
        private String side;        // LONG / SHORT
        private Integer blockOrder; // 블록 순서
        private Boolean isLeaf;     // 리프 블록 여부
    }
}
