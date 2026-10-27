package com.coin.webcointrader.common.trade.controller;

import com.coin.webcointrader.common.login.dto.UserDTO;
import com.coin.webcointrader.common.login.entity.User;
import com.coin.webcointrader.common.login.entity.UserExchangeKey;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.login.repository.UserExchangeKeyRepository;
import com.coin.webcointrader.common.util.AesEncryptor;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureWireMock(port = 0)
class TradeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LoginRepository loginRepository;

    @Autowired
    private UserExchangeKeyRepository userExchangeKeyRepository;

    @Autowired
    private AesEncryptor aesEncryptor;

    private Long savedUserId;
    private UsernamePasswordAuthenticationToken auth;

    @BeforeEach
    void setUp() {
        // 백그라운드 스케줄러(MarketService) 대응 스텁
        stubFor(WireMock.get(urlPathEqualTo("/v5/market/tickers"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBodyFile("MarketClient_getTickers.json")));

        // 주문 생성 스텁 (TradeClient → POST /v5/order/create)
        stubFor(WireMock.post(urlPathEqualTo("/v5/order/create"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBodyFile("TradeClient_createOrder.json")));

        // 테스트 유저 저장 후 user_exchange_key에 API Key 저장 (거래 클라이언트가 복호화할 수 있도록 AES 암호화)
        User user = new User();
        user.setUsername("trader");
        user.setPhoneNumber("01099998888");
        user.setEmail("trader@test.com");
        user.setPassword("encodedPw");
        User saved = loginRepository.save(user);
        savedUserId = saved.getId();

        // 거래 클라이언트가 UserExchangeKeyRepository로 조회하므로 함께 저장
        UserExchangeKey exchangeKey = new UserExchangeKey();
        exchangeKey.setUserId(savedUserId);
        exchangeKey.setExchangeType(ExchangeType.BYBIT);
        exchangeKey.setApiKey(aesEncryptor.encrypt("testApiKey"));
        exchangeKey.setApiSecret(aesEncryptor.encrypt("testApiSecret"));
        userExchangeKeyRepository.save(exchangeKey);

        UserDTO userDTO = UserDTO.builder()
                .id(savedUserId)
                .phoneNumber("01099998888")
                .username("trader")
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

    // ─────────────────────────────────────────────
    // POST /api/order/create
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/order/create: 거래소 중립 요청을 Bybit 시장가 주문으로 보내고 주문 ID를 반환한다")
    void createOrder_success() throws Exception {
        mockMvc.perform(post("/api/order/create").param("exchange", "BYBIT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "symbol": "BTCUSDT",
                                    "side": "BUY",
                                    "qty": "0.01"
                                }
                                """)
                        .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("1234567890"));

        // linear 시장가 Buy로 변환되어 전송
        WireMock.verify(postRequestedFor(urlPathEqualTo("/v5/order/create"))
                .withRequestBody(matchingJsonPath("$.category", equalTo("linear")))
                .withRequestBody(matchingJsonPath("$.side", equalTo("Buy")))
                .withRequestBody(matchingJsonPath("$.orderType", equalTo("Market")))
                .withRequestBody(matchingJsonPath("$.qty", equalTo("0.01"))));
    }

    @Test
    @DisplayName("POST /api/order/create: 인증 없이 접근하면 로그인 페이지로 리다이렉트된다")
    void createOrder_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(post("/api/order/create").param("exchange", "BYBIT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "symbol": "BTCUSDT",
                                    "side": "BUY",
                                    "qty": "0.01"
                                }
                                """))
                .andExpect(status().is3xxRedirection());
    }
}
