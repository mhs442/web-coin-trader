package com.coin.webcointrader.bybit.trade.dto;

import com.coin.webcointrader.bybit.common.BybitMasterDTO;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Bybit GET /v5/execution/list 응답 DTO.
 * 주문의 실체결 정보(체결가, 수량, 수수료)를 포함한다.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class GetExecutionListResponse extends BybitMasterDTO {

    private Result result; // 체결 내역 목록

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class Result {
        private List<ExecutionInfo> list;   // 체결 내역 리스트
        private String nextPageCursor;      // 페이지네이션 커서
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class ExecutionInfo {
        private String symbol;    // 심볼 (예: BTCUSDT)
        private String orderId;   // Bybit 주문 ID
        private String side;      // 방향 (Buy/Sell)
        private String execPrice; // 실체결가 (집계 결과에서는 수량 가중 평균가)
        private String execQty;   // 실체결 수량 (집계 결과에서는 체결 수량 합계)
        private String execFee;   // 실수수료 (VIP 레벨 적용됨, 집계 결과에서는 합계)
        private String orderQty;  // 원 주문 수량 (체결 반영 완료 판단용)
        private String execTime;  // 체결 시각 (ms 타임스탬프)
        private String orderLinkId;   // 사용자 지정 주문 ID (거래소 TP/SL·수동 주문은 빈 값일 수 있음)
        private String execType;      // 체결 유형 (Trade, BustTrade: 강제청산, AdlTrade, Funding 등)
        private String stopOrderType; // 스톱 주문 유형 (TakeProfit, StopLoss 등, 일반 주문은 빈 값)
        private String createType;    // 주문 생성 유형 (CreateByUser, CreateByTakeProfit, CreateByLiq 등)
    }
}
