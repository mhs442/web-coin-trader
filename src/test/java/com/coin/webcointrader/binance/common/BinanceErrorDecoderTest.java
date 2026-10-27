package com.coin.webcointrader.binance.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binance 에러 응답 디코더 단위 테스트.
 * Binance 는 실패를 HTTP 4xx/5xx + {code, msg} 본문으로 알린다.
 */
class BinanceErrorDecoderTest {

    private final BinanceErrorDecoder decoder = new BinanceErrorDecoder(new ObjectMapper());

    @Test
    @DisplayName("decode: {code, msg} 본문을 BinanceApiException 으로 변환하고 메시지는 \"[code] msg\" 형태")
    void decodesErrorBody() {
        Exception e = decoder.decode("TradeClient#order", response(401, "Unauthorized",
                "{\"code\":-2015,\"msg\":\"Invalid API-key, IP, or permissions for action.\"}"));

        assertThat(e).isInstanceOf(BinanceApiException.class);
        BinanceApiException ex = (BinanceApiException) e;
        assertThat(ex.getCode()).isEqualTo(-2015);
        assertThat(ex.getMsg()).isEqualTo("Invalid API-key, IP, or permissions for action.");
        assertThat(ex.getMessage()).isEqualTo("[-2015] Invalid API-key, IP, or permissions for action.");
    }

    @Test
    @DisplayName("decode: 본문이 {code, msg} 형식이 아니면 HTTP 상태·사유로 대체한다")
    void nonJsonBody_fallsBackToStatus() {
        BinanceApiException ex = (BinanceApiException) decoder.decode("MarketClient#ticker",
                response(502, "Bad Gateway", "<html>bad gateway</html>"));

        assertThat(ex.getCode()).isEqualTo(502);
        assertThat(ex.getMsg()).isEqualTo("Bad Gateway");
    }

    @Test
    @DisplayName("decode: 본문이 없으면 HTTP 상태·사유로 대체한다")
    void emptyBody_fallsBackToStatus() {
        BinanceApiException ex = (BinanceApiException) decoder.decode("MarketClient#ticker",
                response(429, "Too Many Requests", null));

        assertThat(ex.getCode()).isEqualTo(429);
        assertThat(ex.getMsg()).isEqualTo("Too Many Requests");
    }

    private static Response response(int status, String reason, String body) {
        Response.Builder builder = Response.builder()
                .status(status)
                .reason(reason)
                .headers(Map.of())
                .request(Request.create(Request.HttpMethod.GET, "/fapi/v1/test", Map.of(), null,
                        StandardCharsets.UTF_8, null));
        if (body != null) builder.body(body, StandardCharsets.UTF_8);
        return builder.build();
    }
}
