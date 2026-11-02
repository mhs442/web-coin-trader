package com.coin.webcointrader.common.market.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/**
 * 코인별 최근 1시간 가격 추세 응답. (대시보드 미니 차트 /api/tickers/trends)
 */
@Getter
@AllArgsConstructor
public class PriceTrendResponse {
    private String symbol;            // 종목 심볼 (예: BTCUSDT)
    private List<BigDecimal> prices;  // 1분 간격 현재가, 오래된 값부터 (최대 60개)
}
