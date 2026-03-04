package com.coin.webcointrader.signup.controller;

import com.coin.webcointrader.common.entity.User;
import com.coin.webcointrader.login.repository.LoginRepository;
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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureWireMock(port = 0)
class SignupControllerTest {

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
                .andExpect(model().attributeExists("error"));
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
                        .param("apiKey", "invalidKey")
                        .param("apiSecret", "invalidSecret"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("error"));
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
        existing.setApiKey("encKey");
        existing.setApiSecret("encSecret");
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
