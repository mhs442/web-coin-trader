package com.coin.webcointrader.common.enums;

import com.coin.webcointrader.common.exception.CustomException;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/**
 * 지원 거래소 타입.
 * 거래소별 어댑터 라우팅 및 세션 키 구성에 사용된다.
 */
@Getter
@RequiredArgsConstructor
public enum ExchangeType {
    BYBIT("Bybit"),      // Bybit 거래소
    BINANCE("Binance");  // Binance USDⓈ-M 선물 (화면 노출은 시세·거래·계정 클라이언트가 모두 등록된 뒤)

    private final String displayName;  // 화면 표시명 (예: "Bybit")

    /**
     * 요청 문자열을 거래소 타입으로 변환한다. (대소문자 무시)
     *
     * @param value 거래소 문자열 (예: "bybit")
     * @return 거래소 타입
     * @throws CustomException null 또는 공백인 경우 (EXCHANGE_TYPE_REQUIRED), 지원하지 않는 거래소인 경우 (INVALID_EXCHANGE_TYPE)
     */
    public static ExchangeType from(String value) {
        // [사용자 정책 2026-10-27] 기본 거래소 없음 — 미전달 시 임의 거래소로 처리하지 않고 거부
        if (value == null || value.isBlank()) {
            throw new CustomException(ExceptionMessage.EXCHANGE_TYPE_REQUIRED);
        }
        // 알 수 없는 값은 기본값으로 대체하지 않고 거부 — 의도하지 않은 거래소로 주문이 나가는 것을 방지
        return Arrays.stream(values())
                .filter(type -> type.name().equalsIgnoreCase(value.trim()))
                .findFirst()
                .orElseThrow(() -> new CustomException(ExceptionMessage.INVALID_EXCHANGE_TYPE));
    }
}
