package com.coin.webcointrader.common.dto.request;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class SetTradingStopRequest {
    private String category;    // 파생상품 카테고리 (예: "linear")
    private String symbol;      // 종목 심볼 (예: "BTCUSDT")
    private String takeProfit;  // 익절 가격 (빈 문자열이면 해제)
    private String stopLoss;    // 손절 가격 (빈 문자열이면 해제)
    private int positionIdx;    // 포지션 방향 인덱스 (0: 단방향, 1: Buy, 2: Sell)
}
