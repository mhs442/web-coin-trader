package com.coin.webcointrader.common.market.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * 거래소 공통 티커(시세 요약).
 * 숫자 값은 거래소 원본 정밀도를 유지하기 위해 문자열로 보관한다.
 */
@Getter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
public class TickerDTO {
    private String symbol;        // 종목 심볼 (예: BTCUSDT)
    private String lastPrice;     // 현재가
    private String price24hPcnt;  // 24시간 가격 변동률 (비율, 예: "0.0123" = 1.23%)
    private String highPrice24h;  // 24시간 최고가
    private String lowPrice24h;   // 24시간 최저가
    private String volume24h;     // 24시간 거래량 (코인 기준)
    private String turnover24h;   // 24시간 거래대금 (USDT 기준)

    /** 기준 코인 (USDT 접미사만 제거, 예: 1000000BABYDOGEUSDT → 1000000BABYDOGE) */
    public String getBaseCoin() {
        if (symbol != null && symbol.endsWith("USDT")) {
            return symbol.substring(0, symbol.length() - 4);
        }
        return symbol;
    }

    /** 결제 코인 (현재 USDT 무기한만 취급) */
    public String getQuoteCoin() {
        return "USDT";
    }

    /** 화면 표시용 심볼 (예: BTC / USDT) */
    public String getFormattedSymbol() {
        if (symbol != null && symbol.endsWith("USDT")) {
            return symbol.substring(0, symbol.length() - 4) + " / USDT";
        }
        return symbol;
    }
}
