package com.coin.webcointrader.common.market.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 거래소 공통 공개 체결 1건. (거래량 집계용)
 */
@Getter
@Builder
public class PublicTradeDTO {
    private String symbol;      // 종목 심볼 (예: BTCUSDT)
    private long tradeTime;     // 체결 시각 (epoch ms)
    private String qty;         // 체결 수량 (코인 기준)
}
