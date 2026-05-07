package com.coin.webcointrader.common.client.market.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Bybit WebSocket Kline(봉) 메시지 매핑 DTO.
 * 1분봉 단위 봉 형성 진행 중(confirm=false) 또는 봉 마감(confirm=true) 시마다 수신된다.
 *
 * <p>메시지 예시:
 * <pre>
 * {
 *   "topic": "kline.1.BTCUSDT",
 *   "type": "snapshot",
 *   "data": [{
 *     "start": 1699999740000,
 *     "end":   1699999799999,
 *     "interval": "1",
 *     "open": "50000.00",
 *     "close": "50100.00",
 *     "high": "50200.00",
 *     "low": "49950.00",
 *     "volume": "12.5",
 *     "turnover": "625500.00",
 *     "confirm": true,
 *     "timestamp": 1699999800000
 *   }],
 *   "ts": 1699999800123
 * }
 * </pre>
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class WebSocketKlineDTO {

    private String topic;          // 구독 토픽 (예: "kline.1.BTCUSDT")
    private String type;           // 메시지 타입 ("snapshot" 또는 "delta")
    private List<KlineData> data;  // 봉 데이터 목록 (보통 1개)
    private Long ts;               // 메시지 발송 시각 (ms, 거래소 서버 기준)

    /**
     * 1분봉 데이터.
     * confirm=true이면 해당 봉이 마감된 상태이고, 종가/시가가 확정된 값이다.
     */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class KlineData {
        private Long start;       // 봉 시작 시각 (ms)
        private Long end;         // 봉 종료 시각 (ms, 봉의 마지막 ms)
        private String interval;  // 봉 간격 ("1" = 1분봉)
        private String open;      // 시가
        private String close;     // 종가
        private String high;      // 고가
        private String low;       // 저가
        private String volume;    // 거래량
        private String turnover;  // 거래대금
        private Boolean confirm;  // 봉 마감 여부 (true = 확정된 봉)
        private Long timestamp;   // 마지막 갱신 시각 (ms)
    }
}
