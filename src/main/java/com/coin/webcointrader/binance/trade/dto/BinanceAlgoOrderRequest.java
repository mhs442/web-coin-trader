package com.coin.webcointrader.binance.trade.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * Binance POST /fapi/v1/algoOrder 요청 파라미터. (조건부 TP/SL 주문, 쿼리 문자열로 전송, null 필드는 생략)
 */
@Getter
@Builder
public class BinanceAlgoOrderRequest {
    private String algoType;      // 알고 주문 유형 (CONDITIONAL)
    private String symbol;        // 종목 심볼
    private String side;          // 주문 방향 (진입 반대 방향)
    private String type;          // 주문 유형 (TAKE_PROFIT_MARKET / STOP_MARKET)
    private String triggerPrice;  // 트리거 가격
    private String closePosition; // 포지션 전체 청산 ("true")
    private String workingType;   // 트리거 기준 가격 (CONTRACT_PRICE: 최종 체결가)
    private String clientAlgoId;  // 사용자 지정 알고 주문 ID (null이면 거래소 자동 생성)
}
