package com.coin.webcointrader.binance.trade.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

/**
 * Binance POST /fapi/v1/algoOrder 응답. (접수 확인용 필드만 매핑)
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceAlgoOrderResponse {
    private String algoId;       // 알고 주문 ID (숫자 → 문자열)
    private String clientAlgoId; // 사용자 지정 알고 주문 ID
    private String algoStatus;   // 알고 주문 상태 (NEW …)
}
