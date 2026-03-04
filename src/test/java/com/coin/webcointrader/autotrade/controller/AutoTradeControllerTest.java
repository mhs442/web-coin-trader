package com.coin.webcointrader.autotrade.controller;

import com.coin.webcointrader.autotrade.repository.QueueRepository;
import com.coin.webcointrader.common.dto.UserDTO;
import com.coin.webcointrader.common.entity.Queue;
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

import java.time.LocalDateTime;
import java.util.ArrayList;

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
    private QueueRepository queueRepository;

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
        queueRepository.deleteAll();
        WireMock.reset();
    }

    // ─────────────────────────────────────────────
    // GET /api/autotrade/patterns
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/autotrade/patterns: 사용자의 패턴 큐 목록을 JSON 배열로 반환한다")
    void getPatterns_returnsQueueList() throws Exception {
        // given
        queueRepository.save(makeQueue(USER_ID, "BTCUSDT", 0));

        // when & then
        mockMvc.perform(get("/api/autotrade/patterns")
                        .param("symbol", "BTCUSDT")
                        .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1));
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
                                    "steps": [{"side": "LONG", "quantity": "0.01"}]
                                }
                                """)
                        .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.useYn").value("Y"))
                .andExpect(jsonPath("$.steps").isArray());
    }

    // ─────────────────────────────────────────────
    // DELETE /api/autotrade/patterns/{id}
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("DELETE /api/autotrade/patterns/{id}: 큐를 소프트 삭제하고 status:ok를 반환한다")
    void deletePattern_returnsOk() throws Exception {
        // given
        Queue q = queueRepository.save(makeQueue(USER_ID, "BTCUSDT", 0));

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

    private Queue makeQueue(Long userId, String symbol, int sortOrder) {
        Queue q = new Queue();
        q.setUserId(userId);
        q.setSymbol(symbol);
        q.setSortOrder(sortOrder);
        q.setUseYn("Y");
        q.setDelYn("N");
        q.setCreatedAt(LocalDateTime.now());
        q.setSteps(new ArrayList<>());
        return q;
    }
}
