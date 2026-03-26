package com.coin.webcointrader.common.dto.response;

import com.coin.webcointrader.common.dto.BybitMasterDTO;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Bybit GET /v5/market/instruments-info 응답 DTO.
 * 종목별 거래 규칙(최소 수량, 가격 단위 등)을 포함한다.
 */
@Getter @Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class GetInstrumentsInfoResponse extends BybitMasterDTO {

    private Result result;      // 종목 정보 조회 결과

    @Getter @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class Result {
        private String category;                    // 파생상품 카테고리 (예: "linear")
        private List<InstrumentInfo> list;           // 종목 정보 목록
    }

    @Getter @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class InstrumentInfo {
        private String symbol;                      // 종목 심볼 (예: "BTCUSDT")
        private LotSizeFilter lotSizeFilter;        // 수량 관련 규칙
    }

    @Getter @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class LotSizeFilter {
        private String qtyStep;                     // 최소 수량 단위 (예: BTC="0.001", DOGE="1")
        private String minOrderQty;                 // 최소 주문 수량
        private String maxOrderQty;                 // 최대 주문 수량
    }
}
