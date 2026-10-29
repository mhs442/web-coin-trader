package com.coin.webcointrader.bybit.trade.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

/**
 * Bybit POST /v5/order/cancel 요청 본문.
 */
@Getter
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL) // null 필드는 JSON에서 제외 (Bybit API 호환)
public class CancelOrderRequest {
    private String category;    // 파생상품 카테고리 (예: "linear")
    private String symbol;      // 종목 심볼 (예: "BTCUSDT")
    private String orderLinkId; // 취소할 주문의 클라이언트 주문 ID
}
