package com.coin.webcointrader.binance.market.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Binance GET /fapi/v1/exchangeInfo 응답. (페이징 없음, 전체 종목)
 * 거래 규칙 계산에 필요한 종목 정보와 필터만 매핑한다.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceExchangeInfoResponse {

    private List<SymbolInfo> symbols;  // 종목 목록

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SymbolInfo {
        private String symbol;         // 종목 심볼 (예: BTCUSDT)
        private String contractType;   // 계약 종류 (무기한: PERPETUAL, 분기물: CURRENT_QUARTER 등)
        private String status;         // 거래 상태 (거래 가능: TRADING)
        private String quoteAsset;     // 호가 자산 (예: USDT)
        private List<Filter> filters;  // 거래 규칙 필터 목록
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Filter {
        private String filterType;  // 필터 종류 (PRICE_FILTER, LOT_SIZE, MARKET_LOT_SIZE 등)
        private String tickSize;    // 최소 가격 단위 (PRICE_FILTER)
        private String stepSize;    // 수량 단위 (LOT_SIZE·MARKET_LOT_SIZE)
        private String minQty;      // 최소 주문 수량 (LOT_SIZE·MARKET_LOT_SIZE)
    }
}
