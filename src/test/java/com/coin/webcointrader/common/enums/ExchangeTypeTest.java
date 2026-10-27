package com.coin.webcointrader.common.enums;

import com.coin.webcointrader.common.exception.CustomException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExchangeTypeTest {

    @Test
    @DisplayName("from: null 또는 공백이면 기본값 없이 EXCHANGE_TYPE_REQUIRED 예외를 던진다")
    void from_blankThrows() {
        assertThatThrownBy(() -> ExchangeType.from(null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getExceptionMessage())
                .isEqualTo(ExceptionMessage.EXCHANGE_TYPE_REQUIRED);
        assertThatThrownBy(() -> ExchangeType.from("  "))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getExceptionMessage())
                .isEqualTo(ExceptionMessage.EXCHANGE_TYPE_REQUIRED);
    }

    @Test
    @DisplayName("from: 대소문자를 구분하지 않고 변환한다")
    void from_caseInsensitive() {
        assertThat(ExchangeType.from("bybit")).isEqualTo(ExchangeType.BYBIT);
        assertThat(ExchangeType.from("ByBit")).isEqualTo(ExchangeType.BYBIT);
    }

    @Test
    @DisplayName("from: 지원하지 않는 거래소면 INVALID_EXCHANGE_TYPE 예외를 던진다")
    void from_unknownThrows() {
        assertThatThrownBy(() -> ExchangeType.from("UNKNOWN"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getExceptionMessage())
                .isEqualTo(ExceptionMessage.INVALID_EXCHANGE_TYPE);
    }

    @Test
    @DisplayName("displayName: 화면 표시명을 반환한다 (거래소 추가 시 enum 한 곳만 수정)")
    void displayName() {
        assertThat(ExchangeType.BYBIT.getDisplayName()).isEqualTo("Bybit");
    }
}
