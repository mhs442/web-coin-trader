package com.coin.webcointrader.binance.common;

import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.util.HmacUtil;
import com.coin.webcointrader.common.util.UserApiKeyContext;
import feign.Request;
import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Binance 서명 요청 인터셉터 단위 테스트.
 * SIGNED 엔드포인트는 쿼리 문자열(timestamp·recvWindow 포함)을 HMAC-SHA256 으로 서명해 마지막 파라미터 signature 로 붙인다.
 */
class BinanceFeignConfigTest {

    private final BinanceFeignConfig config = new BinanceFeignConfig();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(config, "recvWindow", "5000");
    }

    @AfterEach
    void clearContext() {
        UserApiKeyContext.clear();
    }

    @Test
    @DisplayName("서명: 키 헤더를 붙이고, timestamp·recvWindow 를 포함한 쿼리를 서명해 signature 를 마지막 파라미터로 붙인다")
    void signsQueryString() {
        UserApiKeyContext.set("rawKey", "rawSecret");
        RequestTemplate template = orderTemplate();

        config.signedRequestInterceptor().apply(template);

        assertThat(template.headers().get("X-MBX-APIKEY")).containsExactly("rawKey");
        String query = template.queryLine().substring(1);
        int idx = query.lastIndexOf("&signature=");
        String payload = query.substring(0, idx);
        String signature = query.substring(idx + "&signature=".length());
        assertThat(payload).startsWith("symbol=BTCUSDT&side=BUY&timestamp=").endsWith("&recvWindow=5000");
        // 서명은 signature 앞까지의 쿼리 문자열 전체에 대한 HMAC
        assertThat(signature).isEqualTo(HmacUtil.sha256Hex("rawSecret", payload));
    }

    @Test
    @DisplayName("서명: 바인딩된 키가 없으면 INVALID_API_KEY, 헤더·서명을 붙이지 않는다")
    void noKey_throws() {
        RequestTemplate template = orderTemplate();

        assertThatThrownBy(() -> config.signedRequestInterceptor().apply(template))
                .isInstanceOf(CustomException.class)
                .extracting("exceptionMessage").isEqualTo(ExceptionMessage.INVALID_API_KEY);
        assertThat(template.headers()).doesNotContainKey("X-MBX-APIKEY");
        assertThat(template.queryLine()).doesNotContain("signature");
    }

    private static RequestTemplate orderTemplate() {
        RequestTemplate template = new RequestTemplate();
        template.method(Request.HttpMethod.POST);
        template.uri("/fapi/v1/order");
        template.query("symbol", "BTCUSDT");
        template.query("side", "BUY");
        return template;
    }
}
