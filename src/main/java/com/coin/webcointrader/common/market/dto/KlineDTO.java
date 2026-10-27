package com.coin.webcointrader.common.market.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * 거래소 공통 캔들(봉).
 */
@Getter
@SuperBuilder
@NoArgsConstructor
public class KlineDTO {
    private String symbol;      // 종목 심볼 (예: BTCUSDT)
    private long startTime;     // 봉 시작 시각 (epoch ms)
    private long endTime;       // 봉 종료 시각 (epoch ms, REST 조회 시 0일 수 있음)
    private String open;        // 시가
    private String high;        // 고가
    private String low;         // 저가
    private String close;       // 종가
    private String volume;      // 거래량 (코인 기준)
    private boolean confirmed;  // 봉 마감 여부 (true: 마감된 봉)
}
