package com.coin.webcointrader.binance.common;

import lombok.Getter;

/**
 * Binance API 실패 응답 예외.
 * Binance 는 실패를 HTTP 4xx/5xx + {code, msg} 로 알리므로 ErrorDecoder 가 이 예외로 변환하고,
 * 각 클라이언트는 이를 잡아 공통 CustomException("[code] msg")으로 바꿔 던진다.
 */
@Getter
public class BinanceApiException extends RuntimeException {

    private final int code;   // Binance 에러 코드 (본문 파싱 실패 시 HTTP 상태 코드)
    private final String msg; // Binance 에러 메시지 (본문 파싱 실패 시 HTTP 사유)

    public BinanceApiException(int code, String msg) {
        // 원인 추적을 위해 "[code] msg" 형태로 기록
        super("[" + code + "] " + msg);
        this.code = code;
        this.msg = msg;
    }
}
