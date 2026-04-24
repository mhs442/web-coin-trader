package com.coin.webcointrader.autotrade.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * 자동매매 큐 수정 요청.
 * symbol, tradeMode는 변경 불가이므로 포함하지 않는다.
 * 내부 단계/패턴/블록 구조는 AddPatternRequest의 내부 클래스를 재사용한다.
 */
@Getter
@Setter
public class UpdatePatternRequest {
    private BigDecimal triggerRate;                    // 수정할 트리거 기준 상승/하락률(%)
    private List<AddPatternRequest.StepRequest> steps; // 교체할 단계 목록
}
