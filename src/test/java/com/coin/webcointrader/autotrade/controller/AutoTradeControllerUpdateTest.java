package com.coin.webcointrader.autotrade.controller;

import com.coin.webcointrader.autotrade.repository.PatternQueueRepository;
import com.coin.webcointrader.common.dto.UserDTO;
import com.coin.webcointrader.common.entity.PatternQueue;
import com.coin.webcointrader.common.enums.TradeMode;
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

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureWireMock(port = 0)
class AutoTradeControllerUpdateTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PatternQueueRepository patternQueueRepository;

    // 다른 테스트 클래스와 충돌하지 않도록 고유 userId 사용
    private static final Long USER_ID = 200L;

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
                .phoneNumber("01099998888")
                .username("update-tester")
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
    // PUT /api/autotrade/patterns/{id}
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("PUT /api/autotrade/patterns/{id}: 유효한 요청으로 triggerRate와 단계를 교체한다")
    void updatePattern_success() throws Exception {
        // given: 비활성 큐 저장
        PatternQueue saved = patternQueueRepository.save(makeInactiveQueue(USER_ID, "BTCUSDT"));

        // when & then
        mockMvc.perform(put("/api/autotrade/patterns/" + saved.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "triggerRate": 2.5,
                                    "steps": [{
                                        "stepOrder": 1,
                                        "patterns": [{
                                            "amount": 20,
                                            "leverage": 3,
                                            "conditionBlocks": [{"side": "SHORT", "blockOrder": 1, "isLeaf": false}],
                                            "leafBlock": {"side": "SHORT", "blockOrder": 2, "isLeaf": true}
                                        }]
                                    }]
                                }
                                """)
                        .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(saved.getId()))
                .andExpect(jsonPath("$.triggerRate").value(2.5))
                .andExpect(jsonPath("$.steps[0].patterns[0].amount").value(20));
    }

    @Test
    @DisplayName("PUT /api/autotrade/patterns/{id}: 존재하지 않는 id면 400을 반환한다")
    void updatePattern_notFound() throws Exception {
        mockMvc.perform(put("/api/autotrade/patterns/99999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "triggerRate": 1.0,
                                    "steps": [{
                                        "stepOrder": 1,
                                        "patterns": [{
                                            "amount": 10,
                                            "leverage": 1,
                                            "conditionBlocks": [{"side": "LONG", "blockOrder": 1, "isLeaf": false}],
                                            "leafBlock": {"side": "LONG", "blockOrder": 2, "isLeaf": true}
                                        }]
                                    }]
                                }
                                """)
                        .with(authentication(auth)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT /api/autotrade/patterns/{id}: 활성화된 큐는 400을 반환한다")
    void updatePattern_activeQueue() throws Exception {
        // given: 활성화된 큐 저장
        PatternQueue active = makeInactiveQueue(USER_ID, "ETHUSDT");
        active.setActive(true);
        PatternQueue saved = patternQueueRepository.save(active);

        mockMvc.perform(put("/api/autotrade/patterns/" + saved.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "triggerRate": 1.0,
                                    "steps": [{
                                        "stepOrder": 1,
                                        "patterns": [{
                                            "amount": 10,
                                            "leverage": 1,
                                            "conditionBlocks": [{"side": "LONG", "blockOrder": 1, "isLeaf": false}],
                                            "leafBlock": {"side": "LONG", "blockOrder": 2, "isLeaf": true}
                                        }]
                                    }]
                                }
                                """)
                        .with(authentication(auth)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("활성화된 큐는 수정할 수 없습니다. 먼저 비활성화 해주세요."));
    }

    @Test
    @DisplayName("PUT /api/autotrade/patterns/{id}: 다른 사용자의 큐면 403을 반환한다")
    void updatePattern_unauthorized() throws Exception {
        // given: 다른 사용자(userId=999) 소유 큐
        PatternQueue other = makeInactiveQueue(999L, "BTCUSDT");
        PatternQueue saved = patternQueueRepository.save(other);

        mockMvc.perform(put("/api/autotrade/patterns/" + saved.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "triggerRate": 1.0,
                                    "steps": [{
                                        "stepOrder": 1,
                                        "patterns": [{
                                            "amount": 10,
                                            "leverage": 1,
                                            "conditionBlocks": [{"side": "LONG", "blockOrder": 1, "isLeaf": false}],
                                            "leafBlock": {"side": "LONG", "blockOrder": 2, "isLeaf": true}
                                        }]
                                    }]
                                }
                                """)
                        .with(authentication(auth)))
                .andExpect(status().isForbidden());
    }

    // ─────────────────────────────────────────────
    // 헬퍼 메서드
    // ─────────────────────────────────────────────

    private PatternQueue makeInactiveQueue(Long userId, String symbol) {
        PatternQueue q = new PatternQueue();
        q.setUserId(userId);
        q.setSymbol(symbol);
        q.setTriggerRate(new BigDecimal("1.0"));
        q.setActive(false);
        q.setFull(false);
        q.setTradeMode(TradeMode.MAIN);
        return q;
    }
}
