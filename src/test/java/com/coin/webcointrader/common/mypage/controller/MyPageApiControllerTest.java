package com.coin.webcointrader.common.mypage.controller;

import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.util.OtherExchangeTestData;
import com.coin.webcointrader.common.enums.Side;
import com.coin.webcointrader.common.login.dto.UserDTO;
import com.coin.webcointrader.common.enums.OrderResult;
import com.coin.webcointrader.common.trade.entity.InvestmentHistory;
import com.coin.webcointrader.common.trade.entity.TradeHistory;
import com.coin.webcointrader.common.trade.repository.InvestmentHistoryRepository;
import com.coin.webcointrader.common.trade.repository.TradeHistoryRepository;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.wiremock.AutoConfigureWireMock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 마이페이지 API 거래소 조건 통합 테스트.
 * 마이페이지는 헤더에서 선택한 거래소(exchange 파라미터) 기준으로 조회·전체 삭제한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureWireMock(port = 0)
class MyPageApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InvestmentHistoryRepository investmentHistoryRepository;

    @Autowired
    private TradeHistoryRepository tradeHistoryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final Long USER_ID = 1L;
    private UsernamePasswordAuthenticationToken auth;

    @BeforeEach
    void setUp() {
        // 백그라운드 스케줄러(MarketService) 대응 스텁
        stubFor(WireMock.get(urlPathEqualTo("/v5/market/tickers"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBodyFile("MarketClient_getTickers.json")));

        UserDTO userDTO = UserDTO.builder().id(USER_ID).phoneNumber("01011112222")
                .username("mypage").password("pw").build();
        auth = new UsernamePasswordAuthenticationToken(userDTO, null, userDTO.getAuthorities());

        // BYBIT 1건 + 다른 거래소 1건 (enum 에 없는 값이라 SQL 로 지정)
        OtherExchangeTestData.allowAnyExchangeType(jdbcTemplate, "investment_history");
        investmentHistoryRepository.save(makeInvestment());
        Long otherId = investmentHistoryRepository.save(makeInvestment()).getId();
        OtherExchangeTestData.setOtherExchange(jdbcTemplate, "investment_history", otherId);
    }

    @AfterEach
    void tearDown() {
        investmentHistoryRepository.deleteAllInBatch();
        tradeHistoryRepository.deleteAllInBatch();
        WireMock.reset();
    }

    @Test
    @DisplayName("GET /api/mypage/investments: 선택한 거래소 히스토리만 조회한다")
    void getInvestments_onlySelectedExchange() throws Exception {
        mockMvc.perform(get("/api/mypage/investments").param("exchange", "BYBIT").with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    @DisplayName("DELETE /api/mypage/investments/all: 선택한 거래소 히스토리만 삭제한다")
    void deleteAllInvestments_onlySelectedExchange() throws Exception {
        mockMvc.perform(delete("/api/mypage/investments/all")
                        .param("mode", "main").param("exchange", "BYBIT")
                        .with(authentication(auth)))
                .andExpect(status().isOk());

        // 다른 거래소 1건은 남음
        assertThat(investmentHistoryRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("DELETE /api/mypage/investments/all: 지원하지 않는 거래소면 400, 삭제하지 않는다")
    void deleteAllInvestments_unknownExchange() throws Exception {
        mockMvc.perform(delete("/api/mypage/investments/all")
                        .param("mode", "main").param("exchange", "UNKNOWN")
                        .with(authentication(auth)))
                .andExpect(status().isBadRequest());

        assertThat(investmentHistoryRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("DELETE /api/mypage/investments/all: 거래소를 보내지 않으면 기본값 없이 400, 삭제하지 않는다")
    void deleteAllInvestments_missingExchange() throws Exception {
        mockMvc.perform(delete("/api/mypage/investments/all")
                        .param("mode", "main")
                        .with(authentication(auth)))
                .andExpect(status().isBadRequest());

        assertThat(investmentHistoryRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("GET /api/mypage/investments/{id}/trades: 부분 익절 후 손절 건은 진입·익절·손절 3행과 행별 손익을 반환한다")
    void getInvestmentTrades_partialTpThenStopLoss() throws Exception {
        // given: 패턴 2번 사이클 — LONG 진입 50000, 마진 100, 레버리지 10, 투자 손익 3.0 (= 익절 11.4 + 손절 -8.4)
        InvestmentHistory inv = makeInvestment();
        inv.setPatternId(2L);
        inv.setLeverage(10);
        inv.setProfitLoss(new BigDecimal("3.0"));
        Long invId = investmentHistoryRepository.save(inv).getId();
        Long entryId = tradeHistoryRepository.save(makeTrade("entry", "100", "50000", "0.5")).getId();
        Long sellId = tradeHistoryRepository.save(makeTrade("sell", "60", "51000", "0.3")).getId();
        Long liqId = tradeHistoryRepository.save(makeTrade("liquidation", "40", "49000", "0.2")).getId();
        // 생성 시각 고정 (진입 → 익절 → 손절 → 투자 이력 순)
        setCreatedAt("trade_history", entryId, "2026-01-10 11:00:00");
        setCreatedAt("trade_history", sellId, "2026-01-10 11:00:10");
        setCreatedAt("trade_history", liqId, "2026-01-10 11:00:20");
        setCreatedAt("investment_history", invId, "2026-01-10 11:00:30");

        // when & then
        mockMvc.perform(get("/api/mypage/investments/" + invId + "/trades").param("mode", "main")
                        .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].orderType").value("entry"))
                .andExpect(jsonPath("$[1].orderType").value("sell"))
                .andExpect(jsonPath("$[1].profitLoss").value("11.4"))
                .andExpect(jsonPath("$[2].orderType").value("liquidation"))
                .andExpect(jsonPath("$[2].profitLoss").value("-8.4"));
    }

    private TradeHistory makeTrade(String orderType, String amount, String price, String fee) {
        TradeHistory t = new TradeHistory();
        t.setExchangeType(ExchangeType.BYBIT);
        t.setUserId(USER_ID);
        t.setPatternId(2L);
        t.setSymbol("BTCUSDT");
        t.setSide(Side.LONG);
        t.setOrderType(orderType);
        t.setAmount(new BigDecimal(amount));
        t.setExecutedPrice(new BigDecimal(price));
        t.setFee(new BigDecimal(fee));
        t.setOrderResult(OrderResult.SUCCESS);
        return t;
    }

    private void setCreatedAt(String table, Long id, String createdAt) {
        jdbcTemplate.update("UPDATE " + table + " SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(createdAt), id);
    }

    private InvestmentHistory makeInvestment() {
        InvestmentHistory h = new InvestmentHistory();
        h.setExchangeType(ExchangeType.BYBIT);
        h.setUserId(USER_ID);
        h.setPatternId(1L);
        h.setSymbol("BTCUSDT");
        h.setSide(Side.LONG);
        h.setProfitLoss(new BigDecimal("1"));
        h.setEntryPrice(new BigDecimal("50000"));
        h.setExitPrice(new BigDecimal("51000"));
        h.setAmount(new BigDecimal("100"));
        h.setLeverage(1);
        return h;
    }
}
