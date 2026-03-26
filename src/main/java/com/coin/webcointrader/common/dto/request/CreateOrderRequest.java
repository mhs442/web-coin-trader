package com.coin.webcointrader.common.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

@Getter
@Builder
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
}