package com.coin.webcointrader.common.autotrade.controller;

import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.autotrade.repository.PatternQueueRepository;
import com.coin.webcointrader.common.login.dto.UserDTO;
import com.coin.webcointrader.common.autotrade.entity.PatternQueue;
import com.coin.webcointrader.common.autotrade.service.AutoTradeService;
import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.exception.CustomException;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    // 큐 끄기·삭제 시 포지션 청산 호출 검증·실패 재현용 (나머지 동작은 실제 빈 그대로)
    @MockitoSpyBean
    private AutoTradeService autoTradeService;

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
        // 큐 생성 시 종목 거래 가능 여부 확인용 거래 규칙 (BTCUSDT·ETHUSDT / 다음 페이지 SOLUSDT·DOGEUSDT)
        stubFor(WireMock.get(urlPathEqualTo("/v5/market/instruments-info")).atPriority(5)
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBodyFile("MarketClient_getInstrumentsInfo_page1.json")));
        stubFor(WireMock.get(urlPathEqualTo("/v5/market/instruments-info")).atPriority(1)
                .withQueryParam("cursor", WireMock.equalTo("page2cursor123"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBodyFile("MarketClient_getInstrumentsInfo_page2.json")));

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
        mockMvc.perform(get("/api/autotrade/patterns").param("exchange", "BYBIT")
                        .param("symbol", "BTCUSDT")
                        .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].symbol").value("BTCUSDT"));
    }

    @Test
    @DisplayName("GET /api/autotrade/patterns: exchange 파라미터(대소문자 무시)의 거래소 큐만 반환한다")
    void getPatterns_withExchangeParam() throws Exception {
        patternQueueRepository.save(makePatternQueue(USER_ID, "BTCUSDT"));

        mockMvc.perform(get("/api/autotrade/patterns")
                        .param("symbol", "BTCUSDT")
                        .param("exchange", "bybit")
                        .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].exchangeType").value("BYBIT"));
    }

    @Test
    @DisplayName("GET /api/autotrade/patterns: 지원하지 않는 거래소면 400을 반환한다")
    void getPatterns_invalidExchange_returns400() throws Exception {
        mockMvc.perform(get("/api/autotrade/patterns")
                        .param("symbol", "BTCUSDT")
                        .param("exchange", "UNKNOWN")
                        .with(authentication(auth)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/autotrade/patterns/all: exchange 미지정이면 모든 거래소의 큐를 거래소 타입과 함께 반환한다")
    void getAllPatterns_withoutExchange_returnsAllExchanges() throws Exception {
        patternQueueRepository.save(makePatternQueue(USER_ID, "BTCUSDT"));
        PatternQueue binance = makePatternQueue(USER_ID, "ETHUSDT");
        binance.setExchangeType(ExchangeType.BINANCE);
        patternQueueRepository.save(binance);

        mockMvc.perform(get("/api/autotrade/patterns/all")
                        .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].exchangeType").value("BYBIT"))
                .andExpect(jsonPath("$[1].exchangeType").value("BINANCE"));
    }

    @Test
    @DisplayName("GET /api/autotrade/patterns/all: 지원하지 않는 거래소면 400을 반환한다")
    void getAllPatterns_invalidExchange_returns400() throws Exception {
        mockMvc.perform(get("/api/autotrade/patterns/all")
                        .param("exchange", "UNKNOWN")
                        .with(authentication(auth)))
                .andExpect(status().isBadRequest());
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
                                    "exchangeType": "BYBIT",
                                    "triggerRate": 1.0,
                                    "patterns": [
                                        {
                                            "stepLevel": 1,
                                            "amount": 10,
                                            "leverage": 5,
                                            "stopLossRate": 1.0,
                                            "takeProfitRate": 5.0,
                                            "conditionBlocks": ["LONG"],
                                            "leafBlock": "LONG"
                                        },
                                        {
                                            "stepLevel": 1,
                                            "amount": 10,
                                            "leverage": 5,
                                            "stopLossRate": 1.0,
                                            "takeProfitRate": 5.0,
                                            "conditionBlocks": ["SHORT"],
                                            "leafBlock": "SHORT"
                                        }
                                    ]
                                }
                                """)
                        .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.patterns").isArray())
                .andExpect(jsonPath("$.patterns[0].amount").value(10))
                .andExpect(jsonPath("$.patterns[0].stepLevel").value(1))
                .andExpect(jsonPath("$.patterns[0].patternOrder").value(1))
                .andExpect(jsonPath("$.patterns[0].conditionBlocks[0]").value("LONG"))
                .andExpect(jsonPath("$.patterns[0].leafBlock").value("LONG"))
                .andExpect(jsonPath("$.patterns[1].patternOrder").value(2));
    }

    @Test
    @DisplayName("POST /api/autotrade/patterns: 손절률이 강제청산 한도를 넘으면 400을 반환한다 (10배, 90%)")
    void addPattern_stopLossOverLiquidationLimit_returns400() throws Exception {
        mockMvc.perform(post("/api/autotrade/patterns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "symbol": "BTCUSDT",
                                    "exchangeType": "BYBIT",
                                    "triggerRate": 1.0,
                                    "patterns": [
                                        {
                                            "stepLevel": 1,
                                            "amount": 10,
                                            "leverage": 10,
                                            "stopLossRate": 90,
                                            "conditionBlocks": ["LONG"],
                                            "leafBlock": "LONG"
                                        },
                                        {
                                            "stepLevel": 1,
                                            "amount": 10,
                                            "leverage": 5,
                                            "stopLossRate": 1.0,
                                            "conditionBlocks": ["SHORT"],
                                            "leafBlock": "SHORT"
                                        }
                                    ]
                                }
                                """)
                        .with(authentication(auth)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/autotrade/patterns: 익절률이 음수면 400을 반환한다")
    void addPattern_negativeTakeProfitRate_returns400() throws Exception {
        mockMvc.perform(post("/api/autotrade/patterns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "symbol": "BTCUSDT",
                                    "exchangeType": "BYBIT",
                                    "triggerRate": 1.0,
                                    "patterns": [
                                        {
                                            "stepLevel": 1,
                                            "amount": 10,
                                            "leverage": 5,
                                            "stopLossRate": 1.0,
                                            "takeProfitRate": -2,
                                            "conditionBlocks": ["LONG"],
                                            "leafBlock": "LONG"
                                        },
                                        {
                                            "stepLevel": 1,
                                            "amount": 10,
                                            "leverage": 5,
                                            "stopLossRate": 1.0,
                                            "conditionBlocks": ["SHORT"],
                                            "leafBlock": "SHORT"
                                        }
                                    ]
                                }
                                """)
                        .with(authentication(auth)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ExceptionMessage.INVALID_TAKE_PROFIT_RATE.getMessage()));
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
        mockMvc.perform(delete("/api/autotrade/patterns/" + q.getId()).param("exchange", "BYBIT")
                        .param("symbol", "BTCUSDT")
                        .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    @DisplayName("DELETE /api/autotrade/patterns/{id}: 삭제 전에 보유 포지션 청산을 먼저 호출한다")
    void deletePattern_closesPositionBeforeDelete() throws Exception {
        PatternQueue q = patternQueueRepository.save(makePatternQueue(USER_ID, "BTCUSDT"));

        mockMvc.perform(delete("/api/autotrade/patterns/" + q.getId()).param("exchange", "BYBIT")
                        .param("symbol", "BTCUSDT")
                        .with(authentication(auth)))
                .andExpect(status().isOk());

        verify(autoTradeService).closeQueuePosition(USER_ID, q.getId());
        assertThat(patternQueueRepository.findById(q.getId())).isEmpty();
    }

    @Test
    @DisplayName("DELETE /api/autotrade/patterns/{id}: 포지션 청산 실패 시 400을 반환하고 큐를 삭제하지 않는다")
    void deletePattern_closeFailed_keepsQueue() throws Exception {
        PatternQueue q = patternQueueRepository.save(makePatternQueue(USER_ID, "BTCUSDT"));
        doThrow(new CustomException(ExceptionMessage.QUEUE_POSITION_CLOSE_FAILED, "청산 주문 실패"))
                .when(autoTradeService).closeQueuePosition(USER_ID, q.getId());

        mockMvc.perform(delete("/api/autotrade/patterns/" + q.getId()).param("exchange", "BYBIT")
                        .param("symbol", "BTCUSDT")
                        .with(authentication(auth)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("포지션 청산에 실패해 큐를 유지합니다: 청산 주문 실패"));

        assertThat(patternQueueRepository.findById(q.getId())).isPresent();
    }

    @Test
    @DisplayName("DELETE /api/autotrade/patterns/{id}: 활성 큐는 400을 반환하고 청산·삭제 모두 하지 않는다")
    void deletePattern_activeQueue_rejected() throws Exception {
        PatternQueue active = makePatternQueue(USER_ID, "BTCUSDT");
        active.setActive(true);
        PatternQueue q = patternQueueRepository.save(active);

        mockMvc.perform(delete("/api/autotrade/patterns/" + q.getId()).param("exchange", "BYBIT")
                        .param("symbol", "BTCUSDT")
                        .with(authentication(auth)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ExceptionMessage.QUEUE_ACTIVE_CANNOT_DELETE.getMessage()));

        // 활성 여부 검사가 청산보다 먼저 — 포지션을 청산하지 않고 큐도 유지
        verify(autoTradeService, never()).closeQueuePosition(USER_ID, q.getId());
        assertThat(patternQueueRepository.findById(q.getId())).isPresent();
    }

    // ─────────────────────────────────────────────
    // PATCH /api/autotrade/patterns/{id}/toggle
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("PATCH toggle: 켜진 큐를 끄면 보유 포지션 청산 후 비활성화한다")
    void togglePattern_off_closesPositionThenDeactivates() throws Exception {
        PatternQueue q = makePatternQueue(USER_ID, "BTCUSDT");
        q.setActive(true);
        q = patternQueueRepository.save(q);
        doNothing().when(autoTradeService).syncSession(anyLong(), any(), any(), any());

        mockMvc.perform(patch("/api/autotrade/patterns/" + q.getId() + "/toggle")
                        .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        var order = inOrder(autoTradeService);
        order.verify(autoTradeService).closeQueuePosition(USER_ID, q.getId());
        order.verify(autoTradeService).syncSession(USER_ID, ExchangeType.BYBIT, "BTCUSDT", q.getTradeMode());
        assertThat(patternQueueRepository.findById(q.getId()).orElseThrow().isActive()).isFalse();
    }

    @Test
    @DisplayName("PATCH toggle: 꺼진 큐를 켤 때는 청산을 호출하지 않는다")
    void togglePattern_on_doesNotClose() throws Exception {
        PatternQueue q = patternQueueRepository.save(makePatternQueue(USER_ID, "BTCUSDT"));
        doNothing().when(autoTradeService).syncSession(anyLong(), any(), any(), any());

        mockMvc.perform(patch("/api/autotrade/patterns/" + q.getId() + "/toggle")
                        .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));

        verify(autoTradeService, never()).closeQueuePosition(any(), any());
    }

    @Test
    @DisplayName("PATCH toggle: 끄기 전 포지션 청산 실패 시 400을 반환하고 큐를 켜진 채로 유지한다")
    void togglePattern_off_closeFailed_keepsActive() throws Exception {
        PatternQueue q = makePatternQueue(USER_ID, "BTCUSDT");
        q.setActive(true);
        q = patternQueueRepository.save(q);
        doThrow(new CustomException(ExceptionMessage.QUEUE_POSITION_CLOSE_FAILED, "익절 지정가 취소 실패"))
                .when(autoTradeService).closeQueuePosition(USER_ID, q.getId());

        mockMvc.perform(patch("/api/autotrade/patterns/" + q.getId() + "/toggle")
                        .with(authentication(auth)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("포지션 청산에 실패해 큐를 유지합니다: 익절 지정가 취소 실패"));

        assertThat(patternQueueRepository.findById(q.getId()).orElseThrow().isActive()).isTrue();
        verify(autoTradeService, never()).syncSession(anyLong(), any(), any(), any());
    }

    // ─────────────────────────────────────────────
    // GET /api/autotrade/status
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/autotrade/status: 활성 세션이 없으면 active:false를 반환한다")
    void getStatus_returnsInactiveWhenNoSession() throws Exception {
        // when & then (syncSession 미호출 → 세션 없음)
        mockMvc.perform(get("/api/autotrade/status").param("exchange", "BYBIT")
                        .param("symbol", "BTCUSDT")
                        .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    @DisplayName("GET /api/autotrade/status: 지원하지 않는 거래소면 400을 반환한다")
    void getStatus_invalidExchange_returns400() throws Exception {
        mockMvc.perform(get("/api/autotrade/status")
                        .param("symbol", "BTCUSDT")
                        .param("exchange", "UNKNOWN")
                        .with(authentication(auth)))
                .andExpect(status().isBadRequest());
    }

    // ─────────────────────────────────────────────
    // 헬퍼 메서드
    // ─────────────────────────────────────────────

    private PatternQueue makePatternQueue(Long userId, String symbol) {
        PatternQueue q = new PatternQueue();
        q.setExchangeType(ExchangeType.BYBIT);
        q.setUserId(userId);
        q.setSymbol(symbol);
        q.setTriggerRate(new BigDecimal("1.0"));
        q.setActive(false);
        q.setFull(false);
        return q;
    }
}
