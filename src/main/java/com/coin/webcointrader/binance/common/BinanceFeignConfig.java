package com.coin.webcointrader.binance.common;

import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.enums.LogMessage;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.util.HmacUtil;
import com.coin.webcointrader.common.util.UserApiKeyContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import feign.codec.ErrorDecoder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Binance 서명(SIGNED) 엔드포인트용 Feign 설정. (계정·거래 클라이언트에 적용)
 * 모든 파라미터는 쿼리 문자열로 보내고, timestamp·recvWindow 를 더한 쿼리를 HMAC-SHA256 으로 서명해
 * 마지막 파라미터 signature 로 붙인다. 키는 X-MBX-APIKEY 헤더로 전달한다.
 */
@Slf4j
public class BinanceFeignConfig {

    @Value("${binance.api.recv-window:5000}")
    private String recvWindow;

    @Bean
    public RequestInterceptor signedRequestInterceptor() {
        return template -> {
            String apiKey = UserApiKeyContext.getApiKey();
            String apiSecret = UserApiKeyContext.getApiSecret();

            if (apiKey == null || apiSecret == null) {
                throw new CustomException(ExceptionMessage.INVALID_API_KEY);
            }

            template.query("timestamp", String.valueOf(Instant.now().toEpochMilli()));
            template.query("recvWindow", recvWindow);
            // signature 는 반드시 마지막 파라미터 (서명 대상 = 그 앞까지의 쿼리 + 본문)
            template.query("signature", generateSignature(template, apiSecret));
            template.header("X-MBX-APIKEY", apiKey);
        };
    }

    @Bean
    public ErrorDecoder binanceErrorDecoder(ObjectMapper objectMapper) {
        return new BinanceErrorDecoder(objectMapper);
    }

    @Bean
    public BinanceWeightMonitor binanceWeightMonitor() {
        return new BinanceWeightMonitor();
    }

    private String generateSignature(RequestTemplate template, String apiSecret) {
        String queryString = template.queryLine().startsWith("?") ? template.queryLine().substring(1) : template.queryLine();
        String body = template.body() != null ? new String(template.body(), StandardCharsets.UTF_8) : "";
        // 쿼리에 서명이 붙으므로 메서드·경로만 기록 (키·서명·쿼리 미기록)
        log.debug(LogMessage.BINANCE_API_CALL.getMessage(), template.method(), template.path());

        // Binance 서명 규칙: HMAC-SHA256(secret, totalParams = queryString + body)
        return HmacUtil.sha256Hex(apiSecret, queryString + body);
    }
}
