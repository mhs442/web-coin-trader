package com.coin.webcointrader.common.login.controller;

import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.util.AesEncryptor;
import com.coin.webcointrader.common.login.dto.UserDTO;
import com.coin.webcointrader.common.login.entity.User;
import com.coin.webcointrader.common.login.entity.UserExchangeKey;
import com.coin.webcointrader.common.login.repository.LoginRepository;
import com.coin.webcointrader.common.login.repository.UserExchangeKeyRepository;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.wiremock.AutoConfigureWireMock;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 거래소 API Key 조회·등록/교체 API 통합 테스트.
 * 키 검증은 WireMock(GET /v5/user/query-api)으로 모의한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureWireMock(port = 0)
class ExchangeKeyApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LoginRepository loginRepository;

    @Autowired
    private UserExchangeKeyRepository userExchangeKeyRepository;

    @Autowired
    private AesEncryptor aesEncryptor;

    private Long userId;
    private UsernamePasswordAuthenticationToken auth;

    @BeforeEach
    void setUp() {
        // 백그라운드 스케줄러(MarketService) 대응 스텁
        stubFor(WireMock.get(urlPathEqualTo("/v5/market/tickers"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBodyFile("MarketClient_getTickers.json")));

        User user = new User();
        user.setUsername("keyholder");
        user.setPhoneNumber("01055554444");
        user.setEmail("keyholder@test.com");
        user.setPassword("encodedPw");
        userId = loginRepository.save(user).getId();

        UserDTO userDTO = UserDTO.builder()
                .id(userId)
                .phoneNumber("01055554444")
                .username("keyholder")
                .password("encodedPw")
                .build();
        auth = new UsernamePasswordAuthenticationToken(userDTO, null, userDTO.getAuthorities());
    }

    @AfterEach
    void tearDown() {
        userExchangeKeyRepository.deleteAll();
        loginRepository.deleteAll();
        WireMock.reset();
    }

    @Test
    @DisplayName("GET /api/exchange-keys: 키가 없으면 미등록으로 반환하고 키 값은 노출하지 않는다")
    void getStatuses_notRegistered() throws Exception {
        mockMvc.perform(get("/api/exchange-keys").with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].exchangeType").value("BYBIT"))
                .andExpect(jsonPath("$[0].displayName").value("Bybit"))
                .andExpect(jsonPath("$[0].registered").value(false))
                .andExpect(jsonPath("$[0].apiKey").doesNotExist())
                // 지원 거래소 전체(BYBIT, BINANCE) 노출, 키 값은 노출하지 않음
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].exchangeType").value("BINANCE"))
                .andExpect(jsonPath("$[1].apiKey").doesNotExist());
    }

    @Test
    @DisplayName("PUT /api/exchange-keys/{exchange}: 유효한 키면 기존 키를 암호화된 새 값으로 교체한다")
    void saveKey_replace() throws Exception {
        UserExchangeKey existing = new UserExchangeKey();
        existing.setUserId(userId);
        existing.setExchangeType(ExchangeType.BYBIT);
        existing.setApiKey(aesEncryptor.encrypt("oldKey"));
        existing.setApiSecret(aesEncryptor.encrypt("oldSecret"));
        userExchangeKeyRepository.save(existing);
        stubFor(WireMock.get(urlPathEqualTo("/v5/user/query-api"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBodyFile("BybitApiKeyValidator_validate.json")));

        mockMvc.perform(put("/api/exchange-keys/bybit")
                        .with(authentication(auth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"newKey\",\"apiSecret\":\"newSecret\"}"))
                .andExpect(status().isOk());

        // 같은 행이 교체되었는지 (사용자·거래소당 1행)
        assertThat(userExchangeKeyRepository.findAll()).hasSize(1);
        UserExchangeKey saved = userExchangeKeyRepository.findByUserIdAndExchangeType(userId, ExchangeType.BYBIT).orElseThrow();
        assertThat(aesEncryptor.decrypt(saved.getApiKey())).isEqualTo("newKey");
        assertThat(aesEncryptor.decrypt(saved.getApiSecret())).isEqualTo("newSecret");

        mockMvc.perform(get("/api/exchange-keys").with(authentication(auth)))
                .andExpect(jsonPath("$[0].registered").value(true));
    }

    @Test
    @DisplayName("PUT /api/exchange-keys/{exchange}: 거래소 검증에 실패하면 400과 안내 메시지, 저장하지 않는다")
    void saveKey_invalidKey() throws Exception {
        stubFor(WireMock.get(urlPathEqualTo("/v5/user/query-api"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"retCode\":\"10003\",\"retMsg\":\"Invalid api key\"}")));

        mockMvc.perform(put("/api/exchange-keys/BYBIT")
                        .with(authentication(auth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"badKey\",\"apiSecret\":\"badSecret\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("유효하지 않은 거래소 API Key입니다."));

        assertThat(userExchangeKeyRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("PUT /api/exchange-keys/{exchange}: 읽기 전용 키도 유효한 키면 저장한다 (권한은 실거래 시 거래소 거부로 기록)")
    void saveKey_readOnlyKey_saved() throws Exception {
        // [사용자 정책 2026-10-27] 키 등록 시 모든 거래소 공통으로 유효성만 검증
        stubFor(WireMock.get(urlPathEqualTo("/v5/user/query-api"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBodyFile("BybitApiKeyValidator_validate_readOnly.json")));

        mockMvc.perform(put("/api/exchange-keys/BYBIT")
                        .with(authentication(auth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"roKey\",\"apiSecret\":\"roSecret\"}"))
                .andExpect(status().isOk());

        assertThat(userExchangeKeyRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("PUT /api/exchange-keys/{exchange}: 지원하지 않는 거래소면 400")
    void saveKey_invalidExchange() throws Exception {
        mockMvc.perform(put("/api/exchange-keys/UNKNOWN")
                        .with(authentication(auth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"k\",\"apiSecret\":\"s\"}"))
                .andExpect(status().isBadRequest());
    }
}
