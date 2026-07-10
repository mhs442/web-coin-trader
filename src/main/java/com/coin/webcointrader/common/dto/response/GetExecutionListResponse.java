package com.coin.webcointrader.common.dto.response;

import com.coin.webcointrader.common.dto.BybitMasterDTO;
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
        private String execPrice; // 실체결가
        private String execQty;   // 실체결 수량
        private String execFee;   // 실수수료 (VIP 레벨 적용됨)
        private String execTime;  // 체결 시각 (ms 타임스탬프)
    }
}
