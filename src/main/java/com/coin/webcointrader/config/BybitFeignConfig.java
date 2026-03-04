package com.coin.webcointrader.config;

import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.util.UserApiKeyContext;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

public class BybitFeignConfig {

    @Value("${bybit.api.recv-window:5000}")
    private String recvWindow;

    @Bean
    public RequestInterceptor requestInterceptor() {
        return template -> {
            String apiKey = UserApiKeyContext.getApiKey();
            String apiSecret = UserApiKeyContext.getApiSecret();

            if (apiKey == null || apiSecret == null) {
                throw new CustomException(ExceptionMessage.INVALID_API_KEY);
            }

            String timestamp = String.valueOf(Instant.now().toEpochMilli());
            String signature = generateSignature(template, timestamp, apiKey, apiSecret);

            template.header("X-BAPI-API-KEY", apiKey);
            template.header("X-BAPI-TIMESTAMP", timestamp);
            template.header("X-BAPI-SIGN", signature);
            template.header("X-BAPI-RECV-WINDOW", recvWindow);
            template.header("Content-Type", "application/json; charset=utf-8");
        };
    }

    private String generateSignature(RequestTemplate template, String timestamp,
                                     String apiKey, String apiSecret) {
        String queryString = template.queryLine().startsWith("?") ? template.queryLine().substring(1) : template.queryLine();
        String body = template.body() != null ? new String(template.body(), StandardCharsets.UTF_8) : "";

        // Bybit V5 서명 규칙: timestamp + apiKey + recvWindow + (queryString or body)
        String str2Sign = timestamp + apiKey + recvWindow + queryString + body;

        try {
            Mac sha256HMAC = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            sha256HMAC.init(secretKey);
            byte[] bytes = sha256HMAC.doFinal(str2Sign.getBytes(StandardCharsets.UTF_8));
            StringBuilder hash = new StringBuilder();
            for (byte b : bytes) hash.append(String.format("%02x", b));
            return hash.toString();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new CustomException(ExceptionMessage.SIGNATURE_GENERATION_FAILED, e);
        }
    }
}
