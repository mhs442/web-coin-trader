package com.coin.webcointrader.trade.controller;

import com.coin.webcointrader.common.dto.UserDTO;
import com.coin.webcointrader.common.entity.User;
import com.coin.webcointrader.common.util.AesEncryptor;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
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

        // 테스트 유저 저장 (AES로 실제 암호화하여 TradeService가 복호화할 수 있도록 함)
        User user = new User();
        user.setUsername("trader");
        user.setPhoneNumber("01099998888");
        user.setEmail("trader@test.com");
        user.setPassword("encodedPw");
        user.setApiKey(aesEncryptor.encrypt("testApiKey"));
        user.setApiSecret(aesEncryptor.encrypt("testApiSecret"));
        User saved = loginRepository.save(user);
        savedUserId = saved.getId();

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
        loginRepository.deleteAll();
        WireMock.reset();
    }

    // ─────────────────────────────────────────────
    // POST /api/order/create
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/order/create: 유효한 요청으로 주문을 생성하고 응답을 반환한다")
    void createOrder_success() throws Exception {
        mockMvc.perform(post("/api/order/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "category": "linear",
                                    "symbol": "BTCUSDT",
                                    "side": "Buy",
                                    "orderType": "Market",
                                    "qty": "0.01"
                                }
                                """)
                        .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.orderId").value("1234567890"));
    }

    @Test
    @DisplayName("POST /api/order/create: 인증 없이 접근하면 로그인 페이지로 리다이렉트된다")
    void createOrder_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(post("/api/order/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "category": "linear",
                                    "symbol": "BTCUSDT",
                                    "side": "Buy",
                                    "orderType": "Market",
                                    "qty": "0.01"
                                }
                                """))
                .andExpect(status().is3xxRedirection());
    }
}
