package com.coin.webcointrader.login.dto;

import com.coin.webcointrader.common.enums.ExchangeType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * 거래소 API Key 조회 결과 DTO. (다른 도메인 전달용, 엔티티 노출 방지)
 * apiKey/apiSecret 은 AES 암호화된 상태이며 복호화는 사용하는 쪽에서 수행한다.
 */
@Getter
@Builder
@AllArgsConstructor
public class ExchangeKeyDTO {

    private final Long userId;                  // 소유자 사용자 ID
    private final ExchangeType exchangeType;    // 거래소 타입
    private final String apiKey;                // AES-256 암호화된 API Key
    private final String apiSecret;             // AES-256 암호화된 API Secret
}
