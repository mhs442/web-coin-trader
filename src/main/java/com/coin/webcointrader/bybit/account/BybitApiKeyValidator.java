package com.coin.webcointrader.bybit.account;

import com.coin.webcointrader.common.util.HmacUtil;
import com.coin.webcointrader.bybit.account.dto.QueryApiKeyResponse;
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
     * [사용자 정책 2026-10-27] 권한(읽기 전용 여부)은 검증하지 않음 — 모든 거래소 공통으로 키 유효성만 확인하고,
     * 권한 부족은 실거래 시 거래소 거부 내용을 거래 히스토리에 기록한다.
     *
     * @param userApiKey    사용자 API Key
     * @param userApiSecret 사용자 API Secret
     * @return 유효하면 true
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
            // 서명된 키 조회가 성공하면 유효한 키
            return "0".equals(apiResponse.getRetCode()) && apiResponse.getResult() != null;
        } catch (Exception e) {
            return false;
        }
    }
}
