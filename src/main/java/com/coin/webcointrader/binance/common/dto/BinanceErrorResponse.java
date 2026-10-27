package com.coin.webcointrader.binance.common.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Binance 에러 응답 본문. (HTTP 4xx/5xx)
 * 예: {"code": -1121, "msg": "Invalid symbol."}
 */
@Getter
@Setter
@NoArgsConstructor
public class BinanceErrorResponse {
    private Integer code; // Binance 에러 코드 (음수, 예: -2015 키·권한 오류)
    private String msg;   // 에러 메시지
}
