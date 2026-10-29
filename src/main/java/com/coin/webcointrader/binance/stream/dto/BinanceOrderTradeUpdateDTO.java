package com.coin.webcointrader.binance.stream.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * Binance user data stream {@code ORDER_TRADE_UPDATE} 이벤트 매핑 DTO.
 * 주문 생성·체결·만료·취소 때마다 수신되며, 체결 1건(x=TRADE)마다 이벤트 1개가 온다.
 * 필드명이 한 글자(대소문자 구분)라 명시적으로 매핑한다.
 *
 * <p>메시지 예시: {@code {"e":"ORDER_TRADE_UPDATE","E":..,"T":..,"o":{"s":"BTCUSDT","c":"..","x":"TRADE","X":"FILLED",..}}}</p>
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceOrderTradeUpdateDTO {

    @JsonProperty("E")
    private long eventTime;       // 이벤트 발생 시각 (epoch ms)
    @JsonProperty("T")
    private long transactionTime; // 거래 처리 시각 (epoch ms)
    @JsonProperty("o")
    private OrderUpdate order;    // 주문·체결 정보

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OrderUpdate {
        @JsonProperty("s")
        private String symbol;           // 종목 심볼 (예: BTCUSDT)
        @JsonProperty("c")
        private String clientOrderId;    // 클라이언트 주문 ID (앱 주문: q{queueId}-..., 강제청산: autoclose-..., ADL: adl_autoclose)
        @JsonProperty("S")
        private String side;             // 주문 방향 (BUY / SELL)
        @JsonProperty("o")
        private String orderType;        // 주문 유형 (MARKET, LIMIT, LIQUIDATION ...)
        @JsonProperty("ot")
        private String origType;         // 원래 주문 유형 (조건부 주문 발동 시 TAKE_PROFIT_MARKET / STOP_MARKET 등)
        @JsonProperty("q")
        private String origQty;          // 주문 수량
        @JsonProperty("ap")
        private String avgPrice;         // 평균 체결가
        @JsonProperty("x")
        private String execType;         // 실행 유형 (NEW, TRADE, CANCELED, EXPIRED, CALCULATED(청산), AMENDMENT)
        @JsonProperty("X")
        private String orderStatus;      // 주문 상태 (NEW, PARTIALLY_FILLED, FILLED, CANCELED, EXPIRED)
        @JsonProperty("i")
        private String orderId;          // 거래소 주문 ID
        @JsonProperty("l")
        private String lastFilledQty;    // 이번 체결 수량
        @JsonProperty("z")
        private String cumFilledQty;     // 누적 체결 수량
        @JsonProperty("L")
        private String lastFilledPrice;  // 이번 체결가
        @JsonProperty("n")
        private String commission;       // 이번 체결 수수료 (체결 없으면 없음)
        @JsonProperty("N")
        private String commissionAsset;  // 수수료 자산 (USDT, BNB 할인 시 BNB)
        @JsonProperty("T")
        private long tradeTime;          // 체결 시각 (epoch ms)
        @JsonProperty("t")
        private String tradeId;          // 체결 ID (체결 건 중복 제거용)
        @JsonProperty("R")
        private boolean reduceOnly;      // 포지션 축소 전용 주문 여부
        @JsonProperty("cp")
        private boolean closePosition;   // 전량 청산 조건부 주문 여부
        @JsonProperty("rp")
        private String realizedProfit;   // 이번 체결의 실현 손익 (수수료 제외)
        @JsonProperty("ps")
        private String positionSide;     // 포지션 방향 (단방향 모드: BOTH)
    }
}
