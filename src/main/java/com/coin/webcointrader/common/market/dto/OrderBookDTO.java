package com.coin.webcointrader.common.market.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.List;

/**
 * 거래소 공통 호가창.
 * asks는 가격 오름차순, bids는 가격 내림차순으로 담는다.
 */
@Getter
@SuperBuilder
@NoArgsConstructor
public class OrderBookDTO {
    private String symbol;       // 종목 심볼 (예: BTCUSDT)
    private List<Level> asks;    // 매도 호가 (가격 오름차순)
    private List<Level> bids;    // 매수 호가 (가격 내림차순)
    private long timestamp;      // 호가 기준 시각 (epoch ms)

    /**
     * 호가 1단계.
     */
    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Level {
        private String price;    // 호가 가격
        private String qty;      // 잔량 (코인 기준)
    }
}
