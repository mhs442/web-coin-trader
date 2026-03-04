package com.coin.webcointrader.signup.service;

import com.coin.webcointrader.signup.dto.QueryApiKeyResponse;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Component
public class BybitApiKeyValidator {

    @Value("${bybit.api.url}")
    private String bybitApiUrl;

    @Value("${bybit.api.recv-window:5000}")
    private String recvWindow;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * 사용자의 API Key/Secret으로 Bybit GET /v5/user/query-api 를 호출하여 유효성 검증.
     * retCode == "0" 이면 유효, 그 외 무효.
     */
    public boolean validate(String userApiKey, String userApiSecret) {
        try {
            String timestamp = String.valueOf(Instant.now().toEpochMilli());
            String str2Sign = timestamp + userApiKey + recvWindow;
            String signature = hmacSha256(userApiSecret, str2Sign);

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-BAPI-API-KEY", userApiKey);
            headers.set("X-BAPI-TIMESTAMP", timestamp);
            headers.set("X-BAPI-SIGN", signature);
            headers.set("X-BAPI-RECV-WINDOW", recvWindow);
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    bybitApiUrl + "/v5/user/query-api",
                    HttpMethod.GET,
                    entity,
                    String.class
            );

            QueryApiKeyResponse apiResponse =
                    objectMapper.readValue(response.getBody(), QueryApiKeyResponse.class);
            return "0".equals(apiResponse.getRetCode());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 주어진 데이터에 대해 HMAC-SHA256 서명을 생성한다.
     *
     * @param secret 서명에 사용할 비밀키 (Bybit API Secret)
     * @param data   서명할 데이터 문자열
     * @return 16진수 소문자로 인코딩된 HMAC-SHA256 서명 문자열
     * @throws RuntimeException 서명 생성 중 오류 발생 시
     */
    private String hmacSha256(String secret, String data) {
        try {
            Mac sha256Hmac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            sha256Hmac.init(secretKey);
            byte[] bytes = sha256Hmac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hash = new StringBuilder();
            for (byte b : bytes) {
                hash.append(String.format("%02x", b));
            }
            return hash.toString();
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 서명 생성 실패", e);
        }
    }
}
