package com.coin.webcointrader.common.autotrade.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * 자동매매 큐 수정 요청.
 * symbol, tradeMode는 변경 불가이므로 포함하지 않는다.
 * 패턴 구조는 AddPatternRequest.PatternRequest를 재사용한다.
 */
@Getter
@Setter
public class UpdatePatternRequest {
    private BigDecimal triggerRate;                    // 수정할 트리거 기준 상승/하락률(%)
    private boolean cycle;                             // 단계 소진 후 1단계 재시작 여부
    private boolean volumeStopEnabled;                 // 거래량 급변 시 큐 중지 사용 여부
    private List<AddPatternRequest.PatternRequest> patterns; // 교체할 패턴 목록
}
