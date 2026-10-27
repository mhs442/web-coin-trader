package com.coin.webcointrader.binance.common;

import feign.Request;
import feign.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binance 사용 가중치 감시 단위 테스트.
 * 응답 헤더 X-MBX-USED-WEIGHT-1M 이 한도의 80% 이상이면 경고 대상으로 판단하는지 검증한다.
 */
class BinanceWeightMonitorTest {

    private final BinanceWeightMonitor monitor = new BinanceWeightMonitor();

    @Test
    @DisplayName("사용 가중치가 한도(2400)의 80%(1920) 이상이면 경고한다")
    void warnsAtThreshold() {
        assertThat(monitor.warnIfHigh(response(Map.of("X-MBX-USED-WEIGHT-1M", List.of("1920"))))).isTrue();
    }

    @Test
    @DisplayName("80% 미만이면 경고하지 않는다")
    void belowThreshold() {
        assertThat(monitor.warnIfHigh(response(Map.of("X-MBX-USED-WEIGHT-1M", List.of("1919"))))).isFalse();
    }

    @Test
    @DisplayName("헤더 이름 대소문자와 관계없이 읽는다")
    void caseInsensitiveHeader() {
        assertThat(monitor.warnIfHigh(response(Map.of("x-mbx-used-weight-1m", List.of("2000"))))).isTrue();
    }

    @Test
    @DisplayName("헤더가 없거나 숫자가 아니면 경고하지 않는다")
    void missingOrInvalidHeader() {
        assertThat(monitor.warnIfHigh(response(Map.of()))).isFalse();
        assertThat(monitor.warnIfHigh(response(Map.of("X-MBX-USED-WEIGHT-1M", List.of("abc"))))).isFalse();
    }

    private Response response(Map<String, Collection<String>> headers) {
        Request request = Request.create(Request.HttpMethod.GET, "https://fapi.binance.com/fapi/v1/klines",
                Map.of(), null, StandardCharsets.UTF_8, null);
        return Response.builder().status(200).request(request).headers(headers).build();
    }
}
