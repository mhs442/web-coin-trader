package com.coin.webcointrader.binance.common;

import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.util.UserApiKeyContext;
import feign.Request;
import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Binance API 키 전용(USER_STREAM) 요청 인터셉터 단위 테스트.
 * listenKey 엔드포인트는 서명 없이 X-MBX-APIKEY 헤더만 요구한다.
 */
class BinanceApiKeyFeignConfigTest {

    private final BinanceApiKeyFeignConfig config = new BinanceApiKeyFeignConfig();

    @AfterEach
    void clearContext() {
        UserApiKeyContext.clear();
    }

    @Test
    @DisplayName("키 헤더: X-MBX-APIKEY 헤더만 붙이고 timestamp·recvWindow·signature 는 붙이지 않는다")
    void addsApiKeyHeaderOnly() {
        UserApiKeyContext.set("rawKey", "rawSecret");
        RequestTemplate template = listenKeyTemplate();

        config.apiKeyRequestInterceptor().apply(template);

        assertThat(template.headers().get("X-MBX-APIKEY")).containsExactly("rawKey");
        assertThat(template.queryLine()).isEmpty();
    }

    @Test
    @DisplayName("키 헤더: 바인딩된 키가 없으면 INVALID_API_KEY, 헤더를 붙이지 않는다")
    void noKey_throws() {
        RequestTemplate template = listenKeyTemplate();

        assertThatThrownBy(() -> config.apiKeyRequestInterceptor().apply(template))
                .isInstanceOf(CustomException.class)
                .extracting("exceptionMessage").isEqualTo(ExceptionMessage.INVALID_API_KEY);
        assertThat(template.headers()).doesNotContainKey("X-MBX-APIKEY");
    }

    private static RequestTemplate listenKeyTemplate() {
        RequestTemplate template = new RequestTemplate();
        template.method(Request.HttpMethod.POST);
        template.uri("/fapi/v1/listenKey");
        return template;
    }
}
