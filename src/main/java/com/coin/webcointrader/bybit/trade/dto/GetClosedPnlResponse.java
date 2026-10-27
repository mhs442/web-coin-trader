package com.coin.webcointrader.bybit.trade.dto;

import com.coin.webcointrader.bybit.common.BybitMasterDTO;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Bybit GET /v5/position/closed-pnl 응답 DTO.
 * 청산된 포지션의 실손익(수수료·펀딩비 차감 후)을 포함한다.
 * Bybit 응답에는 펀딩비 필드가 없으므로 fundingFee는 TradeService에서 역산해 채운다.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class GetClosedPnlResponse extends BybitMasterDTO {

    private Result result; // 청산 손익 목록

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class Result {
        private List<ClosedPnlInfo> list;  // 청산 손익 리스트
        private String nextPageCursor;     // 페이지네이션 커서
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class ClosedPnlInfo {
        private String symbol;        // 심볼 (예: BTCUSDT)
        private String orderId;       // Bybit 주문 ID
        private String side;          // 청산 주문 방향 (Sell: 롱 청산, Buy: 숏 청산)
        private String qty;           // 청산 수량
        private String closedPnl;     // 실손익 (매매 손익 - 수수료 - 펀딩비)
        private String cumEntryValue; // 진입 포지션 가치 누계 (펀딩비 역산용)
        private String cumExitValue;  // 청산 포지션 가치 누계 (펀딩비 역산용)
        private String avgEntryPrice; // 평균 진입가
        private String avgExitPrice;  // 평균 청산가
        private String openFee;       // 진입 수수료
        private String closeFee;      // 청산 수수료
        @JsonIgnore
        private String fundingFee;    // 펀딩비 역산값 (Bybit 응답 필드 아님, 양수: 지급, 음수: 수취)
        private String createdTime;   // 청산 시각 (ms 타임스탬프)
    }
}
