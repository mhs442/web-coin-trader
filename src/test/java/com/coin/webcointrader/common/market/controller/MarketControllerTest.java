package com.coin.webcointrader.common.market.controller;

import com.coin.webcointrader.common.login.dto.UserDTO;
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
import org.springframework.test.web.servlet.MockMvc;

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

    private UsernamePasswordAuthenticationToken auth;

    @BeforeEach
    void setUp() {
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
    @DisplayName("GET /api/tickers: USDT 종목만 거래대금 내림차순 공통 티커 목록으로 반환한다")
    void getTickers_returnsTickerData() throws Exception {
        mockMvc.perform(get("/api/tickers").param("exchange", "BYBIT")
                        .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))   // BTCETH 제외
                .andExpect(jsonPath("$[0].symbol").value("BTCUSDT"))
                .andExpect(jsonPath("$[0].formattedSymbol").value("BTC / USDT"))
                .andExpect(jsonPath("$[0].baseCoin").value("BTC"))
                .andExpect(jsonPath("$[1].symbol").value("ETHUSDT"));
    }

    @Test
    @DisplayName("GET /api/tickers: 거래소 파라미터와 관계없이 Bybit 목록을 반환한다 (대시보드 목록 통일)")
    void getTickers_ignoresExchangeParam() throws Exception {
        mockMvc.perform(get("/api/tickers")
                        .param("exchange", "BINANCE")
                        .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].symbol").value("BTCUSDT"));
    }

    // ─────────────────────────────────────────────
    // GET /api/kline
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/kline: 시작 시각 오름차순 캔들 목록을 반환한다")
    void getKline_returnsKlineData() throws Exception {
        mockMvc.perform(get("/api/kline").param("exchange", "BYBIT")
                        .param("symbol", "BTCUSDT")
                        .param("interval", "60")
                        .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].startTime").value(1699999740000L))
                .andExpect(jsonPath("$[1].startTime").value(1699999800000L))
                .andExpect(jsonPath("$[1].open").value("50000"))
                .andExpect(jsonPath("$[1].high").value("51000"))
                .andExpect(jsonPath("$[1].low").value("49000"))
                .andExpect(jsonPath("$[1].close").value("50500"))
                .andExpect(jsonPath("$[1].volume").value("100.5"));
    }

    // ─────────────────────────────────────────────
    // GET /api/orderbook
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/orderbook: 매도(asks)·매수(bids) 호가 단계를 반환한다")
    void getOrderBook_returnsOrderBookData() throws Exception {
        mockMvc.perform(get("/api/orderbook").param("exchange", "BYBIT")
                        .param("symbol", "BTCUSDT")
                        .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("BTCUSDT"))
                .andExpect(jsonPath("$.asks[0].price").value("50001.00"))
                .andExpect(jsonPath("$.asks[0].qty").value("1.2"))
                .andExpect(jsonPath("$.bids[0].price").value("49999.00"))
                .andExpect(jsonPath("$.bids[0].qty").value("1.5"));
    }
}
