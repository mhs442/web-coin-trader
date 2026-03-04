package com.coin.webcointrader.common.dto.response;

import com.coin.webcointrader.common.dto.ByBItMasterDTO;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class FindTickerResponse extends ByBItMasterDTO {

    private Result result;      // 티커 조회 결과

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class Result {
        private String category;            // 파생상품 카테고리 (예: "linear")
        private List<TickerInfo> list;      // 티커 목록
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class TickerInfo {
        private String symbol;              // 종목 심볼 (예: BTCUSDT)
        private String lastPrice;           // 현재가
        private String price24hPcnt;        // 24시간 가격 변동률 (%)
        private String highPrice24h;        // 24시간 최고가
        private String lowPrice24h;         // 24시간 최저가
        private String volume24h;           // 24시간 거래량 (코인 기준)
        private String turnover24h;         // 24시간 거래대금 (USDT 기준)

        // 단순히 USDT만 제거하고 숫자는 그대로 둠 (예: 1000000BABYDOGE)
        public String getBaseCoin() {
            if (symbol != null && symbol.endsWith("USDT")) {
                return symbol.replace("USDT", "");
            }
            return symbol;
        }

        public String getQuoteCoin() {
            return "USDT";
        }

        // 심볼 포맷팅 (예: 1000000BABYDOGE / USDT)
        public String getFormattedSymbol() {
            if (symbol != null && symbol.endsWith("USDT")) {
                return symbol.substring(0, symbol.length() - 4) + " / USDT";
            }
            return symbol;
        }
    }
}