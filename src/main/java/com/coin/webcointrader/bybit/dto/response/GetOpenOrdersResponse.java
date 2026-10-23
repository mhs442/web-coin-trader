package com.coin.webcointrader.bybit.dto.response;

import com.coin.webcointrader.bybit.dto.BybitMasterDTO;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Bybit GET /v5/order/realtime 응답 DTO.
 * 미체결 주문 및 최근 종료 주문(체결·취소·거부)의 상태를 포함한다.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class GetOpenOrdersResponse extends BybitMasterDTO {

    private Result result; // 주문 목록

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class Result {
        private List<OrderInfo> list;   // 주문 리스트 (createdTime 내림차순)
        private String nextPageCursor;  // 페이지네이션 커서
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class OrderInfo {
        private String orderId;     // Bybit 주문 ID
        private String orderLinkId; // 클라이언트가 설정한 주문 ID
        private String symbol;      // 심볼 (예: BTCUSDT)
        private String side;        // 방향 (Buy/Sell)
        private String orderStatus; // 주문 상태 (New, PartiallyFilled, Filled, Cancelled, Rejected 등)
        private String qty;         // 주문 수량
        private String cumExecQty;  // 누적 체결 수량
        private String avgPrice;    // 평균 체결가 (미체결 시 빈 문자열)
    }
}
