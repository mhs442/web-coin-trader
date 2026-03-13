package com.coin.webcointrader.common.client.market.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Bybit WebSocket 요청 메시지 DTO.
 * subscribe, unsubscribe, ping 요청에 사용된다.
 *
 * 요청 예시:
 * { "op": "subscribe", "args": ["tickers.BTCUSDT"] }
 * { "op": "ping" }
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class WebSocketRequest {

    private String op;          // 요청 오퍼레이션 ("subscribe", "unsubscribe", "ping")
    private List<String> args;  // 요청 인자 목록 (토픽 목록)

    /**
     * subscribe 요청을 생성한다.
     *
     * @param topics 구독할 토픽 목록 (예: ["tickers.BTCUSDT"])
     * @return subscribe 요청 DTO
     */
    public static WebSocketRequest subscribe(List<String> topics) {
        return new WebSocketRequest("subscribe", topics);
    }

    /**
     * unsubscribe 요청을 생성한다.
     *
     * @param topics 구독 해제할 토픽 목록
     * @return unsubscribe 요청 DTO
     */
    public static WebSocketRequest unsubscribe(List<String> topics) {
        return new WebSocketRequest("unsubscribe", topics);
    }

    /**
     * ping 요청을 생성한다. (heartbeat용)
     *
     * @return ping 요청 DTO
     */
    public static WebSocketRequest ping() {
        return new WebSocketRequest("ping", null);
    }
}
