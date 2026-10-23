package com.coin.webcointrader.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HmacUtilTest {

    @Test
    @DisplayName("sha256Hex: 알려진 테스트 벡터와 동일한 16진수 소문자 서명을 반환한다")
    void sha256Hex_matchesKnownVector() {
        String signature = HmacUtil.sha256Hex("key", "The quick brown fox jumps over the lazy dog");

        assertThat(signature)
                .isEqualTo("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
    }

    @Test
    @DisplayName("sha256Hex: Bybit WS 인증 형식(GET/realtime + expires)도 64자 소문자 hex로 서명한다")
    void sha256Hex_wsAuthPayload_isLowercaseHex64() {
        String signature = HmacUtil.sha256Hex("secret", "GET/realtime" + 1662350400000L);

        assertThat(signature).hasSize(64).matches("[0-9a-f]+");
    }
}
