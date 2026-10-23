package com.coin.webcointrader.bybit.client.privatews.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Bybit Private WebSocket 인증 요청 DTO.
 * args에 숫자(expires)가 섞이므로 문자열 전용인 WebSocketRequest와 분리한다.
 *
 * 요청 예시:
 * { "op": "auth", "args": ["apiKey", 1662350400000, "signature"] }
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class WebSocketAuthRequest {

    private String op;          // 요청 오퍼레이션 ("auth")
    private List<Object> args;  // [apiKey, expires(ms, 숫자), signature]

    /**
     * auth 요청을 생성한다.
     *
     * @param apiKey    API Key
     * @param expires   서명 만료 시각 (ms, 현재 시각보다 커야 함)
     * @param signature hex(HMAC_SHA256(secret, "GET/realtime" + expires))
     * @return auth 요청 DTO
     */
    public static WebSocketAuthRequest of(String apiKey, long expires, String signature) {
        return new WebSocketAuthRequest("auth", List.of(apiKey, expires, signature));
    }
}
