package com.coin.webcointrader.login.service;

import com.coin.webcointrader.common.util.HmacUtil;
import com.coin.webcointrader.login.dto.QueryApiKeyResponse;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

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
            String signature = HmacUtil.sha256Hex(userApiSecret, str2Sign);

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
}
