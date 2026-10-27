package com.coin.webcointrader.binance.trade.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

/**
 * Binance GET /fapi/v1/userTrades 응답 항목. (체결 1건)
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceUserTradeResponse {
    private String symbol;          // 종목 심볼
    private String orderId;         // 거래소 주문 ID (숫자 → 문자열)
    private String side;            // 주문 방향 (BUY/SELL)
    private String price;           // 체결가
    private String qty;             // 체결 수량
    private String commission;      // 수수료 (commissionAsset 단위)
    private String commissionAsset; // 수수료 자산 (USDT, BNB …)
    private String realizedPnl;     // 실현 손익 (진입 체결은 0, 수수료·펀딩비 미반영)
    private Long time;              // 체결 시각 (epoch ms)
}
