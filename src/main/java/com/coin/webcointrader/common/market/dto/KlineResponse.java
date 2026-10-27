package com.coin.webcointrader.common.market.dto;

import com.coin.webcointrader.common.market.dto.KlineDTO;
import lombok.Builder;
import lombok.Getter;

/**
 * 캔들 응답. (차트 /api/kline, 시작 시각 오름차순 목록의 원소)
 */
@Getter
@Builder
public class KlineResponse {
    private long startTime;   // 봉 시작 시각 (epoch ms)
    private String open;      // 시가
    private String high;      // 고가
    private String low;       // 저가
    private String close;     // 종가
    private String volume;    // 거래량 (코인 기준)

    /**
     * 공통 캔들을 응답으로 변환한다.
     *
     * @param kline 공통 캔들
     * @return 캔들 응답
     */
    public static KlineResponse from(KlineDTO kline) {
        return KlineResponse.builder()
                .startTime(kline.getStartTime())
                .open(kline.getOpen())
                .high(kline.getHigh())
                .low(kline.getLow())
                .close(kline.getClose())
                .volume(kline.getVolume())
                .build();
    }
}
