package com.coin.webcointrader.market.controller;

import com.coin.webcointrader.common.dto.UserDTO;
import com.coin.webcointrader.market.service.MarketService;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.wiremock.AutoConfigureWireMock;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureWireMock(port = 0)
class MarketControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MarketService marketService;

    private UsernamePasswordAuthenticationToken auth;

    @BeforeEach
    void setUp() {
        // 캐시 초기화: 각 테스트마다 WireMock 스텁을 통해 응답받도록 보장
        ReflectionTestUtils.setField(marketService, "cachedTickers", new AtomicReference<>(null));

        // 시세 스텁 (스케줄러 호출 포함)
        stubFor(WireMock.get(urlPathEqualTo("/v5/market/tickers"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBodyFile("MarketClient_getTickers.json")));

        // K-라인 스텁
        stubFor(WireMock.get(urlPathEqualTo("/v5/market/kline"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBodyFile("MarketClient_getKline.json")));

        // 호가창 스텁
        stubFor(WireMock.get(urlPathEqualTo("/v5/market/orderbook"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBodyFile("MarketClient_getOrderBook.json")));

        UserDTO userDTO = UserDTO.builder()
                .id(1L)
                .phoneNumber("01011111111")
                .username("tester")
                .password("encodedPw")
                .build();

        auth = new UsernamePasswordAuthenticationToken(userDTO, null, userDTO.getAuthorities());
    }

    @AfterEach
    void tearDown() {
        WireMock.reset();
    }

    // ─────────────────────────────────────────────
    // GET /api/tickers
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/tickers: WireMock에서 로드한 티커 데이터를 반환한다")
    void getTickers_returnsTickerData() throws Exception {
        mockMvc.perform(get("/api/tickers")
                        .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").exists())
                .andExpect(jsonPath("$.result.list").isArray());
    }

    // ─────────────────────────────────────────────
    // GET /api/kline
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/kline: symbol과 interval에 맞는 K-라인 데이터를 반환한다")
    void getKline_returnsKlineData() throws Exception {
        mockMvc.perform(get("/api/kline")
                        .param("symbol", "BTCUSDT")
                        .param("interval", "60")
                        .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").exists());
    }

    // ─────────────────────────────────────────────
    // GET /api/orderbook
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/orderbook: symbol에 맞는 호가창 데이터를 반환한다")
    void getOrderBook_returnsOrderBookData() throws Exception {
        mockMvc.perform(get("/api/orderbook")
                        .param("symbol", "BTCUSDT")
                        .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").exists());
    }
}
