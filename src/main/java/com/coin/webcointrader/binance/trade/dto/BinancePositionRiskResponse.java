package com.coin.webcointrader.binance.trade.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

/**
 * Binance GET /fapi/v3/positionRisk 응답 항목. (포지션 수량 확인용 필드만 매핑)
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinancePositionRiskResponse {
    private String symbol;       // 종목 심볼
    private String positionSide; // 포지션 방향 (단방향 모드 BOTH)
    private String positionAmt;  // 포지션 수량 (롱 양수, 숏 음수)
}
