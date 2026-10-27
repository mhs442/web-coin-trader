package com.coin.webcointrader.common.login.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 거래소 API Key 등록·교체 요청.
 */
@Getter
@Setter
public class SaveExchangeKeyRequest {
    private String apiKey;      // 거래소 API Key (평문, 저장 시 암호화)
    private String apiSecret;   // 거래소 API Secret (평문, 저장 시 암호화)
}
