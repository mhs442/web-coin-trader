package com.coin.webcointrader.common.login.dto;

import com.coin.webcointrader.common.enums.ExchangeType;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 거래소별 API Key 등록 상태 DTO. (키 값은 담지 않음)
 */
@Getter
@Builder
public class ExchangeKeyStatusDTO {

    private final ExchangeType exchangeType;    // 거래소 타입
    private final boolean registered;           // API Key 등록 여부
    private final LocalDateTime updatedAt;      // 최종 등록·교체 일시 (미등록 시 null)
}
