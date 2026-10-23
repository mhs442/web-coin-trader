package com.coin.webcointrader.bybit.client.privatews.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Bybit Private WebSocket execution 토픽 메시지 매핑 DTO.
 * 주문이 체결될 때마다 수신되며, 부분 체결 시 주문 1건에 여러 체결 건이 올 수 있다.
 *
 * <p>메시지 예시:
 * <pre>
 * {
 *   "id": "592324803b2785-26fa-4214-9963-bdd4727f07be",
 *   "topic": "execution.linear",
 *   "creationTime": 1672364174455,
 *   "data": [{
 *     "symbol": "BTCUSDT", "orderId": "...", "orderLinkId": "q12-E-1760950000000",
 *     "execId": "...", "side": "Buy", "execPrice": "50000.5", "execQty": "0.010",
 *     "execFee": "0.3", "execType": "Trade", "execPnl": "0", ...
 *   }]
 * }
 * </pre>
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class WebSocketExecutionDTO {

    private String id;                  // 메시지 ID
    private String topic;               // 구독 토픽 (예: "execution.linear")
    private Long creationTime;          // 메시지 생성 시각 (ms)
    private List<ExecutionData> data;   // 체결 건 목록

    /**
     * 체결 1건 데이터.
     */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ExecutionData {
        private String symbol;          // 심볼 (예: "BTCUSDT")
        private String orderId;         // 주문 ID
        private String orderLinkId;     // 사용자 지정 주문 ID (예: "q12-E-1760950000000")
        private String execId;          // 체결 ID (중복 제거 기준)
        private String side;            // 주문 방향 (Buy / Sell)
        private String execPrice;       // 체결가
        private String execQty;         // 체결 수량
        private String execFee;         // 체결 수수료
        private String execType;        // 체결 유형 (Trade, Funding, BustTrade, AdlTrade ...)
        private String execPnl;         // 청산 체결 손익 (진입 체결은 0)
        private String closedSize;      // 이 체결로 청산된 포지션 크기
        private String leavesQty;       // 주문의 남은 미체결 수량
        private String orderQty;        // 주문 수량
        private String createType;      // 주문 생성 유형 (CreateByUser, CreateByTakeProfit, CreateByStopLoss ...)
        private String stopOrderType;   // 스톱 주문 유형 (스톱 주문이 아니면 null 또는 빈 값)
        private String execTime;        // 체결 시각 (ms)
    }
}
