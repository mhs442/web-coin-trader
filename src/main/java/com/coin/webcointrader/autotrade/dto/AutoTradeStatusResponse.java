package com.coin.webcointrader.autotrade.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 자동매매 상태 조회 응답.
 */
@Getter
@Builder
public class AutoTradeStatusResponse {
    private boolean active;          // 활성화 여부
    private String currentPattern;   // 현재 실행 중인 패턴 (예: "1112")
    private int currentStep;         // 현재 단계 인덱스
    private String previousPrice;    // 직전 가격
    private List<String> recentLog;  // 최근 매매 로그
}
