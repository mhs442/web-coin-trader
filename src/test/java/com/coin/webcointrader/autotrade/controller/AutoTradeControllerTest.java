package com.coin.webcointrader.autotrade.controller;

import com.coin.webcointrader.autotrade.repository.PatternQueueRepository;
import com.coin.webcointrader.common.dto.UserDTO;
import com.coin.webcointrader.common.entity.PatternQueue;
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

import java.math.BigDecimal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureWireMock(port = 0)
class AutoTradeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PatternQueueRepository patternQueueRepository;

    // 다른 테스트 클래스와 충돌하지 않도록 고유 userId 사용
    private static final Long USER_ID = 100L;

    private UsernamePasswordAuthenticationToken auth;

    @BeforeEach
    void setUp() {
        // 백그라운드 스케줄러(MarketService)가 WireMock을 호출할 수 있으므로 기본 스텁 설정
        stubFor(WireMock.get(urlPathEqualTo("/v5/market/tickers"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBodyFile("MarketClient_getTickers.json")));

        UserDTO userDTO = UserDTO.builder()
                .id(USER_ID)
                .phoneNumber("01012345678")
                .username("tester")
                .password("encodedPw")
                .build();

        auth = new UsernamePasswordAuthenticationToken(userDTO, null, userDTO.getAuthorities());
    }

    @AfterEach
    void tearDown() {
        patternQueueRepository.deleteAll();
        WireMock.reset();
    }

    // ─────────────────────────────────────────────
    // GET /api/autotrade/patterns
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/autotrade/patterns: 사용자의 패턴 큐 목록을 JSON 배열로 반환한다")
    void getPatterns_returnsQueueList() throws Exception {
        // given
        patternQueueRepository.save(makePatternQueue(USER_ID, "BTCUSDT"));

        // when & then
        mockMvc.perform(get("/api/autotrade/patterns")
                        .param("symbol", "BTCUSDT")
                        .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].symbol").value("BTCUSDT"))
                .andExpect(jsonPath("$[0].triggerSeconds").value(60));
    }

    // ─────────────────────────────────────────────
    // POST /api/autotrade/patterns
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/autotrade/patterns: 유효한 요청으로 새 패턴 큐를 생성한다")
    void addPattern_createsQueue() throws Exception {
        // when & then
        mockMvc.perform(post("/api/autotrade/patterns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "symbol": "BTCUSDT",
                                    "triggerSeconds": 60,
                                    "triggerRate": 1.0,
                                    "steps": [{
                                        "stepOrder": 1,
                                        "patterns": [{
                                            "amount": 10,
                                            "leverage": 5,
                                            "stopLossRate": 1.0,
                                            "takeProfitRate": 5.0,
                                            "conditionBlocks": [{"side": "LONG", "blockOrder": 1, "isLeaf": false}],
                                            "leafBlock": {"side": "LONG", "blockOrder": 2, "isLeaf": true}
                                        }]
                                    }]
                                }
                                """)
                        .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.triggerSeconds").value(60))
                .andExpect(jsonPath("$.steps").isArray())
                .andExpect(jsonPath("$.steps[0].patterns[0].amount").value(10))
                .andExpect(jsonPath("$.steps[0].patterns[0].blocks").isArray());
    }

    // ─────────────────────────────────────────────
    // DELETE /api/autotrade/patterns/{id}
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("DELETE /api/autotrade/patterns/{id}: 큐를 삭제하고 status:ok를 반환한다")
    void deletePattern_returnsOk() throws Exception {
        // given
        PatternQueue q = patternQueueRepository.save(makePatternQueue(USER_ID, "BTCUSDT"));

        // when & then
        mockMvc.perform(delete("/api/autotrade/patterns/" + q.getId())
                        .param("symbol", "BTCUSDT")
                        .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    // ─────────────────────────────────────────────
    // GET /api/autotrade/status
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/autotrade/status: 활성 세션이 없으면 active:false를 반환한다")
    void getStatus_returnsInactiveWhenNoSession() throws Exception {
        // when & then (syncSession 미호출 → 세션 없음)
        mockMvc.perform(get("/api/autotrade/status")
                        .param("symbol", "BTCUSDT")
                        .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    // ─────────────────────────────────────────────
    // 헬퍼 메서드
    // ─────────────────────────────────────────────

    private PatternQueue makePatternQueue(Long userId, String symbol) {
        PatternQueue q = new PatternQueue();
        q.setUserId(userId);
        q.setSymbol(symbol);
        q.setTriggerSeconds(60);
        q.setTriggerRate(new BigDecimal("1.0"));
        q.setActive(false);
        q.setFull(false);
        return q;
    }
}
