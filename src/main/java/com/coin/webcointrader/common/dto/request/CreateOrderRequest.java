package com.coin.webcointrader.common.dto.request;

import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

@Getter
@Builder
@Jacksonized
public class CreateOrderRequest {
    private String category;    // 파생상품 카테고리 (예: "linear")
    private String symbol;      // 종목 심볼 (예: "BTCUSDT")
    private String side;        // 매매 방향 (Buy / Sell)
    private String orderType;   // 주문 유형 (Market / Limit)
    private String qty;         // 주문 수량
    private String price;       // 주문 가격 (Limit 주문 시 필수)
}