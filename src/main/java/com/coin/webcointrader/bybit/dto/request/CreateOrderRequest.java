package com.coin.webcointrader.bybit.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

@Getter
@Builder(toBuilder = true)
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL) // null 필드는 JSON에서 제외 (Bybit API 호환)
public class CreateOrderRequest {
    private String category;    // 파생상품 카테고리 (예: "linear")
    private String symbol;      // 종목 심볼 (예: "BTCUSDT")
    private String side;        // 매매 방향 (Buy / Sell)
    private String orderType;   // 주문 유형 (Market / Limit)
    private String qty;         // 주문 수량
    private String price;       // 주문 가격 (Limit 주문 시 필수)
    private String marketUnit;  // 수량 단위 (baseCoin: 코인 수량, quoteCoin: USDT 금액)
    private Boolean reduceOnly; // true: 포지션 축소/청산 전용 주문 (새 포지션 열림 방지)
    private String orderLinkId; // 클라이언트 주문 ID (36자 이하, 영문·숫자·-·_, 고유값 — 응답 불명확 시 결과 조회용)
    private String takeProfit;  // 익절 트리거 가격 (진입 주문에 동봉 시 포지션 TP로 설정)
    private String stopLoss;    // 손절 트리거 가격 (진입 주문에 동봉 시 포지션 SL로 설정)
    private String tpslMode;    // TP/SL 모드 (Full: 포지션 전체 청산 — 이 모드는 Market만 지원)
    private String tpOrderType; // 익절 발동 시 주문 유형 (Market)
    private String slOrderType; // 손절 발동 시 주문 유형 (Market)
    private String tpTriggerBy; // 익절 트리거 기준 가격 (LastPrice / MarkPrice / IndexPrice)
    private String slTriggerBy; // 손절 트리거 기준 가격 (LastPrice / MarkPrice / IndexPrice)
}