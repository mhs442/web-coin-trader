package com.coin.webcointrader.binance.market.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Binance WebSocket 구독 요청 메시지.
 *
 * <p>요청 예시: {@code {"method":"SUBSCRIBE","params":["btcusdt@ticker","btcusdt@kline_1m"],"id":1}}</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BinanceWsRequest {

    private String method;        // 요청 종류 ("SUBSCRIBE", "UNSUBSCRIBE")
    private List<String> params;  // 스트림 이름 목록 (소문자)
    private long id;              // 요청 ID (응답 {"result":null,"id":..} 매칭용)

    public static BinanceWsRequest subscribe(List<String> streams, long id) {
        return new BinanceWsRequest("SUBSCRIBE", streams, id);
    }

    public static BinanceWsRequest unsubscribe(List<String> streams, long id) {
        return new BinanceWsRequest("UNSUBSCRIBE", streams, id);
    }
}
