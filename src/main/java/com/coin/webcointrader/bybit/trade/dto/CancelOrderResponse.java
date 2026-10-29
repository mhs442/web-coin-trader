package com.coin.webcointrader.bybit.trade.dto;

import com.coin.webcointrader.bybit.common.BybitMasterDTO;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

/**
 * Bybit POST /v5/order/cancel 응답.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class CancelOrderResponse extends BybitMasterDTO {

    private Result result;      // 주문 취소 결과

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class Result {
        private String orderId;         // 취소된 주문 ID
        private String orderLinkId;     // 취소된 주문의 클라이언트 주문 ID
    }
}
