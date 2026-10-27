package com.coin.webcointrader.common.login.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 거래소별 API Key 등록 상태 응답. (키 값은 노출하지 않음)
 */
@Getter
@AllArgsConstructor
public class ExchangeKeyStatusResponse {
    private String exchangeType;        // 거래소 타입 (예: BYBIT)
    private String displayName;         // 화면 표시명 (예: Bybit)
    private boolean registered;         // API Key 등록 여부
    private LocalDateTime updatedAt;    // 최종 등록·교체 일시 (미등록 시 null)

    public static ExchangeKeyStatusResponse from(ExchangeKeyStatusDTO status) {
        return new ExchangeKeyStatusResponse(status.getExchangeType().name(),
                status.getExchangeType().getDisplayName(), status.isRegistered(),
                status.getUpdatedAt());
    }
}
