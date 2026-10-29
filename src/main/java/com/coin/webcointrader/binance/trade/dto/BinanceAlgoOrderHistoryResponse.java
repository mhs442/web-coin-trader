package com.coin.webcointrader.binance.trade.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

/**
 * Binance GET /fapi/v1/allAlgoOrders 응답 1건. (청산 방식 판정에 필요한 필드만 매핑)
 * 트리거된 조건부 주문은 actualOrderId 에 실제 발생한 일반 주문 ID 가 들어간다.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceAlgoOrderHistoryResponse {
    private String algoId;        // 알고 주문 ID (숫자 → 문자열)
    private String clientAlgoId;  // 사용자 지정 알고 주문 ID (앱 주문: {clientOrderId}-TP / -SL)
    private String orderType;     // 조건부 주문 유형 (TAKE_PROFIT_MARKET, STOP_MARKET …)
    private String symbol;        // 종목 심볼
    private String side;          // 주문 방향 (BUY / SELL)
    private String algoStatus;    // 알고 주문 상태 (NEW, CANCELED, TRIGGERED, FINISHED, EXPIRED …)
    private String actualOrderId; // 트리거되어 생성된 일반 주문 ID (미트리거 시 빈 문자열)
    private Long triggerTime;     // 트리거 시각 (epoch ms, 미트리거 시 0)
}
