package com.coin.webcointrader.common.market.dto;

import com.coin.webcointrader.common.market.dto.TickerDTO;
import lombok.Builder;
import lombok.Getter;

/**
 * 티커 응답. (대시보드 목록 /api/tickers, STOMP /topic/price.{exchange}.{symbol})
 */
@Getter
@Builder
public class TickerResponse {
    private String symbol;           // 종목 심볼 (예: BTCUSDT)
    private String lastPrice;        // 현재가
    private String price24hPcnt;     // 24시간 가격 변동률 (비율, 예: "0.0123" = 1.23%)
    private String highPrice24h;     // 24시간 최고가
    private String lowPrice24h;      // 24시간 최저가
    private String volume24h;        // 24시간 거래량 (코인 기준)
    private String turnover24h;      // 24시간 거래대금 (USDT 기준)
    private String baseCoin;         // 기준 코인 (예: BTC)
    private String quoteCoin;        // 결제 코인 (예: USDT)
    private String formattedSymbol;  // 화면 표시용 심볼 (예: BTC / USDT)

    /**
     * 공통 티커를 응답으로 변환한다.
     *
     * @param ticker 공통 티커
     * @return 티커 응답
     */
    public static TickerResponse from(TickerDTO ticker) {
        return TickerResponse.builder()
                .symbol(ticker.getSymbol())
                .lastPrice(ticker.getLastPrice())
                .price24hPcnt(ticker.getPrice24hPcnt())
                .highPrice24h(ticker.getHighPrice24h())
                .lowPrice24h(ticker.getLowPrice24h())
                .volume24h(ticker.getVolume24h())
                .turnover24h(ticker.getTurnover24h())
                .baseCoin(ticker.getBaseCoin())
                .quoteCoin(ticker.getQuoteCoin())
                .formattedSymbol(ticker.getFormattedSymbol())
                .build();
    }
}
