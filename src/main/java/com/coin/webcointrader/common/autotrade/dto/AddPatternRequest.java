package com.coin.webcointrader.common.autotrade.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * 자동매매 큐 등록 요청.
 * 프론트에서 구성한 큐(트리거 + 패턴 목록) 데이터를 받는다.
 * 패턴은 stepLevel로 단계를 표시한 평평한 목록이며, 패턴 순번은 서버가 단계 안에서 요청 순서대로 부여한다.
 */
@Getter @Setter
public class AddPatternRequest {
    private String symbol;              // 코인 심볼 (예: BTCUSDT)
    private String exchangeType;            // 거래소 타입 (필수, 예: "BYBIT")
    private String tradeMode;          // 거래 모드 ("MAIN" 또는 "SIM")
    private BigDecimal triggerRate;     // 트리거 기준 상승/하락률 (%)
    private boolean cycle;             // 단계 소진 후 1단계 재시작 여부
    private boolean volumeStopEnabled; // 거래량 급변 시 큐 중지 사용 여부
    private List<PatternRequest> patterns;  // 패턴 목록 (단계당 2개)

    @Getter @Setter
    public static class PatternRequest {
        private Integer stepLevel;          // 단계 번호 (1부터 시작)
        private BigDecimal amount;          // 투자 금액 (USDT)
        private Integer leverage;           // 레버리지 배수
        private BigDecimal stopLossRate;    // 손절 비율 (%, null이면 미설정)
        private BigDecimal takeProfitRate;  // 익절 비율 (%, null이면 미설정)
        private List<String> conditionBlocks;  // 조건 블록 side 목록 (배열 순서 = 블록 순서, 최대 5개)
        private String leafBlock;              // 실행(리프) 블록 side (LONG / SHORT)
    }
}
