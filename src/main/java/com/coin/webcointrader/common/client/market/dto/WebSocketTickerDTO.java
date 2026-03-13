package com.coin.webcointrader.common.client.market.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Bybit WebSocket 티커 메시지 매핑 DTO.
 * snapshot(전체 갱신) 또는 delta(부분 갱신) 타입으로 수신된다.
 *
 * 메시지 예시:
 * {
 *   "topic": "tickers.BTCUSDT",
 *   "type": "snapshot",
 *   "data": { "symbol": "BTCUSDT", "lastPrice": "50000.00", ... },
 *   "ts": 1699999800000
 * }
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class WebSocketTickerDTO {

    private String topic;       // 구독 토픽 (예: "tickers.BTCUSDT")
    private String type;        // 메시지 타입 ("snapshot" 또는 "delta")
    private TickerData data;    // 티커 데이터
    private Long ts;            // 타임스탬프 (밀리초)

    /**
     * WebSocket 티커 데이터 필드.
     * snapshot은 모든 필드가 포함되고, delta는 변경된 필드만 포함된다.
     */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TickerData {
        private String symbol;          // 종목 심볼 (예: "BTCUSDT")
        private String lastPrice;       // 현재가
        private String price24hPcnt;    // 24시간 가격 변동률
        private String highPrice24h;    // 24시간 최고가
        private String lowPrice24h;     // 24시간 최저가
        private String volume24h;       // 24시간 거래량
        private String turnover24h;     // 24시간 거래대금
    }
}
