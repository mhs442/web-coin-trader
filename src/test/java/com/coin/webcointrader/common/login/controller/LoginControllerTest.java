package com.coin.webcointrader.common.login.controller;

import com.coin.webcointrader.common.login.entity.User;
import com.coin.webcointrader.common.login.repository.LoginRepository;
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
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureWireMock(port = 0)
class LoginControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LoginRepository loginRepository;

    @BeforeEach
    void setUp() {
        // 기본 티커 스텁 (백그라운드 스케줄러 대응)
        stubFor(WireMock.get(urlPathEqualTo("/v5/market/tickers"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBodyFile("MarketClient_getTickers.json")));
    }

    @AfterEach
    void tearDown() {
        loginRepository.deleteAll();
        WireMock.reset();
    }

    // ─────────────────────────────────────────────
    // GET /signup
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("GET /signup: 인증 없이 회원가입 페이지에 접근할 수 있다")
    void getSignupPage_returns200() throws Exception {
        mockMvc.perform(get("/signup"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /signup: 거래소 목록과 API Key 라벨을 ExchangeType 표시명으로 그린다")
    void getSignupPage_rendersExchangeDisplayNames() throws Exception {
        mockMvc.perform(get("/signup"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-label=\"Bybit\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("placeholder=\"Bybit API Key\"")))
                // 클라이언트 구현이 완료된 BINANCE도 목록에 그림
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"BINANCE\"")));
    }

    // ─────────────────────────────────────────────
    // POST /signup - 에러 케이스
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("POST /signup: 비밀번호가 일치하지 않으면 회원가입 페이지로 되돌아간다")
    void signup_passwordMismatch_returnsSignupPage() throws Exception {
        // BybitApiKeyValidator 호출 전에 예외가 발생하므로 WireMock 스텁 불필요
        mockMvc.perform(post("/signup")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "tester")
                        .param("phoneNumber", "01012345678")
                        .param("password", "password1!")
                        .param("passwordConfirm", "different!")
                        .param("apiKey", "someApiKey")
                        .param("apiSecret", "someApiSecret"))
                .andExpect(status().isOk())        // 에러 시 회원가입 페이지 재렌더링
                .andExpect(model().attributeExists("error"))
                // 에러로 다시 그려도 거래소 목록이 유지됨
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-label=\"Bybit\"")));
    }

    @Test
    @DisplayName("POST /signup: Bybit API Key가 유효하지 않으면 회원가입 페이지로 되돌아간다")
    void signup_invalidApiKey_returnsSignupPage() throws Exception {
        // WireMock: retCode != "0" → BybitApiKeyValidator.validate() == false
        stubFor(WireMock.get(urlPathEqualTo("/v5/user/query-api"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"retCode\":\"10003\",\"retMsg\":\"Invalid api key\"}")));

        mockMvc.perform(post("/signup")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "tester")
                        .param("phoneNumber", "01012345678")
                        .param("password", "password1!")
                        .param("passwordConfirm", "password1!")
                        .param("email", "tester@test.com")   // 폼 필수값 (키 검증은 사용자 저장 이후에 수행)
                        .param("apiKey", "invalidKey")
                        .param("apiSecret", "invalidSecret"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("error"));

        // 키 검증 실패 시 사용자 저장까지 롤백
        assertThat(loginRepository.findByPhoneNumber("01012345678")).isEmpty();
    }

    @Test
    @DisplayName("POST /signup: 이미 등록된 전화번호면 회원가입 페이지로 되돌아간다")
    void signup_duplicatePhone_returnsSignupPage() throws Exception {
        // given - 동일 전화번호 사용자 사전 등록 (email 포함, 직접 DB에 삽입)
        User existing = new User();
        existing.setUsername("existingUser");
        existing.setPhoneNumber("01022223333");
        existing.setEmail("existing@test.com");
        existing.setPassword("encodedPw");
        loginRepository.save(existing);

        // when & then - 중복 전화번호 → DB 조회 후 CustomException 발생 (API 호출 없음)
        mockMvc.perform(post("/signup")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "newUser")
                        .param("phoneNumber", "01022223333")
                        .param("password", "password1!")
                        .param("passwordConfirm", "password1!")
                        .param("apiKey", "someKey")
                        .param("apiSecret", "someSecret"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("error"));
    }
}
