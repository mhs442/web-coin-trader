package com.coin.webcointrader.common.mypage.service;

import com.coin.webcointrader.common.autotrade.dto.PatternQueueDTO;
import com.coin.webcointrader.common.autotrade.service.PatternQueueService;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.enums.Side;
import com.coin.webcointrader.common.dto.response.PageResponse;
import com.coin.webcointrader.common.enums.OrderResult;
import com.coin.webcointrader.common.enums.TradeMode;
import com.coin.webcointrader.common.mypage.dto.*;
import com.coin.webcointrader.common.trade.dto.DailyProfitLossDTO;
import com.coin.webcointrader.common.trade.dto.InvestmentHistoryDTO;
import com.coin.webcointrader.common.trade.dto.ProfitLossSummaryDTO;
import com.coin.webcointrader.common.trade.dto.SymbolProfitLossDTO;
import com.coin.webcointrader.common.trade.dto.TradeHistoryDTO;
import com.coin.webcointrader.common.trade.dto.WinLossCountDTO;
import com.coin.webcointrader.common.trade.service.HistoryService;
import com.coin.webcointrader.common.trade.service.TradeFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class MyPageServiceTest {

    @InjectMocks
    private MyPageService myPageService;

    @Mock
    private PatternQueueService patternQueueService;

    @Mock
    private TradeFacade tradeFacade;

    @Mock
    private HistoryService mainHistory; // MAIN 히스토리 서비스

    @Mock
    private HistoryService simHistory;  // SIM 히스토리 서비스

    @BeforeEach
    void setUpHistoryServices() {
        // 모드별 HistoryService 선택은 TradeFacade 책임 — 테스트에서는 mode 로 mock 을 구분한다
        // (getPatterns 테스트 등 히스토리를 쓰지 않는 테스트를 위해 lenient)
        lenient().when(tradeFacade.historyService(TradeMode.MAIN)).thenReturn(mainHistory);
        lenient().when(tradeFacade.historyService(TradeMode.SIM)).thenReturn(simHistory);
    }

    // ─────────────────────────────────────────────
    // getPatterns
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("getPatterns: 검색조건을 autotrade 서비스에 전달하고 DTO를 응답으로 변환한다")
    void getPatterns_defaultRequest() {
        // given
        Long userId = 1L;
        given(patternQueueService.findQueuePage(
                eq(userId), isNull(), eq(TradeMode.MAIN), eq(ExchangeType.BYBIT), any(), any(), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(makeQueueDto(1L, "BTCUSDT", false))));

        // when
        PageResponse<MyPagePatternResponse> result = myPageService.getPatterns(userId, makeMyPagePatternRequest());

        // then
        assertThat(result.getContent()).hasSize(1);
        MyPagePatternResponse r = result.getContent().get(0);
        assertThat(r.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(r.getExchangeType()).isEqualTo("BYBIT");
        assertThat(r.getCreatedAt()).isEqualTo("2026-01-10 12:00:00");
    }

    @Test
    @DisplayName("getPatterns: symbol 키워드와 SIM 모드가 그대로 전달된다")
    void getPatterns_withSymbolFilter() {
        // given
        Long userId = 1L;
        given(patternQueueService.findQueuePage(
                eq(userId), eq("BTC"), eq(TradeMode.SIM), eq(ExchangeType.BYBIT), any(), any(), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(makeQueueDto(1L, "BTCUSDT", false))));

        MyPagePatternRequest request = new MyPagePatternRequest();
        request.setExchange("BYBIT");
        request.setSymbol("BTC");
        request.setMode("sim");
        request.setExchange("bybit"); // 헤더 선택 거래소 (대소문자 무시)

        // when
        PageResponse<MyPagePatternResponse> result = myPageService.getPatterns(userId, request);

        // then
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getSymbol()).isEqualTo("BTCUSDT");
    }

    @Test
    @DisplayName("getPatterns: 패턴 목록(단계 번호·조건 블록·리프 블록)이 응답에 포함된다")
    void getPatterns_includesHierarchy() {
        // given
        Long userId = 1L;
        given(patternQueueService.findQueuePage(
                eq(userId), isNull(), eq(TradeMode.MAIN), eq(ExchangeType.BYBIT), any(), any(), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(makeQueueDto(1L, "BTCUSDT", true))));

        // when
        MyPagePatternResponse response = myPageService.getPatterns(userId, makeMyPagePatternRequest())
                .getContent().get(0);

        // then
        assertThat(response.getPatterns()).hasSize(1);
        MyPagePatternResponse.PatternResponse pattern = response.getPatterns().get(0);
        assertThat(pattern.getStepLevel()).isEqualTo(1);
        assertThat(pattern.getPatternOrder()).isEqualTo(1);
        assertThat(pattern.getAmount()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(pattern.getConditionBlocks()).containsExactly("LONG");
        assertThat(pattern.getLeafBlock()).isEqualTo("LONG");
    }

    // ─────────────────────────────────────────────
    // getInvestmentHistories
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("getInvestmentHistories: 기본 검색조건으로 투자 히스토리와 합산 통계를 조회한다")
    void getInvestmentHistories_defaultRequest() {
        // given
        Long userId = 1L;
        Page<InvestmentHistoryDTO> page = new PageImpl<>(List.of(makeInvestmentHistory(1L, userId, "BTCUSDT")));
        given(mainHistory.findInvestments(eq(userId), eq(ExchangeType.BYBIT), isNull(), any(), any(), any(Pageable.class))).willReturn(page);
        // 합산 모킹: 이익 500, 손해 -300
        given(mainHistory.sumProfitLoss(eq(userId), eq(ExchangeType.BYBIT), isNull(), any(), any()))
                .willReturn(new ProfitLossSummaryDTO(new BigDecimal("500"), new BigDecimal("-300")));
        given(mainHistory.countWinLoss(eq(userId), eq(ExchangeType.BYBIT), any(), any())).willReturn(new WinLossCountDTO(3, 1, 4));

        // when
        InvestmentHistoryPageResponse result = myPageService.getInvestmentHistories(userId, makeInvestmentHistoryRequest());

        // then - 페이징 데이터 검증
        assertThat(result.getPage().getContent()).hasSize(1);
        InvestmentHistoryResponse response = result.getPage().getContent().get(0);
        assertThat(response.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(response.getSide()).isEqualTo("LONG");
        assertThat(response.getExchangeType()).isEqualTo("BYBIT");
        assertThat(response.getEntryPrice()).isEqualTo("50000");
        assertThat(response.getExitPrice()).isEqualTo("51000");
        assertThat(response.getProfitLoss()).isEqualTo("200");

        // then - 합산 통계 검증 (승률 3/4 = 75.0%)
        assertThat(result.getSummary().getTotalProfit()).isEqualTo("500");
        assertThat(result.getSummary().getTotalLoss()).isEqualTo("-300");
        assertThat(result.getSummary().getNetTotal()).isEqualTo("200");
        assertThat(result.getSummary().getWinRate()).isEqualTo("75.0");
    }

    @Test
    @DisplayName("getInvestmentHistories: symbol 키워드를 서비스에 전달해 심볼 필터 페이징 + 심볼별 합산한다")
    void getInvestmentHistories_withSymbolFilter() {
        // given
        Long userId = 1L;
        Page<InvestmentHistoryDTO> page = new PageImpl<>(List.of(makeInvestmentHistory(2L, userId, "ETHUSDT")));
        given(mainHistory.findInvestments(eq(userId), eq(ExchangeType.BYBIT), eq("ETH"), any(), any(), any(Pageable.class))).willReturn(page);
        given(mainHistory.sumProfitLoss(eq(userId), eq(ExchangeType.BYBIT), eq("ETH"), any(), any()))
                .willReturn(new ProfitLossSummaryDTO(new BigDecimal("200"), BigDecimal.ZERO));
        given(mainHistory.countWinLoss(eq(userId), eq(ExchangeType.BYBIT), any(), any())).willReturn(new WinLossCountDTO(0, 0, 0));

        InvestmentHistoryRequest request = new InvestmentHistoryRequest();
        request.setExchange("BYBIT");
        request.setSymbol("ETH");

        // when
        InvestmentHistoryPageResponse result = myPageService.getInvestmentHistories(userId, request);

        // then
        assertThat(result.getPage().getContent().get(0).getSymbol()).isEqualTo("ETHUSDT");
        assertThat(result.getSummary().getTotalProfit()).isEqualTo("200");
        assertThat(result.getSummary().getTotalLoss()).isEqualTo("0");
        assertThat(result.getSummary().getNetTotal()).isEqualTo("200");
        // 기존 동작 유지: 승패 카운트는 심볼 필터 없는 countWinLoss 사용
        then(mainHistory).should(never()).countWinLossBySymbol(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("getInvestmentHistories: 데이터가 없으면 합산 통계가 모두 0이다")
    void getInvestmentHistories_emptyReturnsZeroSummary() {
        // given
        Long userId = 1L;
        given(mainHistory.findInvestments(eq(userId), eq(ExchangeType.BYBIT), any(), any(), any(), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of()));
        given(mainHistory.sumProfitLoss(eq(userId), eq(ExchangeType.BYBIT), any(), any(), any()))
                .willReturn(new ProfitLossSummaryDTO(BigDecimal.ZERO, BigDecimal.ZERO));
        given(mainHistory.countWinLoss(eq(userId), eq(ExchangeType.BYBIT), any(), any())).willReturn(new WinLossCountDTO(0, 0, 0));

        // when
        InvestmentHistoryPageResponse result = myPageService.getInvestmentHistories(userId, makeInvestmentHistoryRequest());

        // then
        assertThat(result.getPage().getContent()).isEmpty();
        assertThat(result.getSummary().getTotalProfit()).isEqualTo("0");
        assertThat(result.getSummary().getTotalLoss()).isEqualTo("0");
        assertThat(result.getSummary().getNetTotal()).isEqualTo("0");
        assertThat(result.getSummary().getWinRate()).isEqualTo("0.0");
    }

    @Test
    @DisplayName("getInvestmentHistories: SIM 모드는 SIM 히스토리 서비스를 사용한다")
    void getInvestmentHistories_simMode_usesSimService() {
        // given
        Long userId = 1L;
        given(simHistory.findInvestments(eq(userId), eq(ExchangeType.BYBIT), any(), any(), any(), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(makeInvestmentHistory(9L, userId, "BTCUSDT"))));
        given(simHistory.sumProfitLoss(eq(userId), eq(ExchangeType.BYBIT), any(), any(), any()))
                .willReturn(new ProfitLossSummaryDTO(BigDecimal.TEN, BigDecimal.ZERO));
        given(simHistory.countWinLoss(eq(userId), eq(ExchangeType.BYBIT), any(), any())).willReturn(new WinLossCountDTO(1, 0, 1));

        InvestmentHistoryRequest request = new InvestmentHistoryRequest();
        request.setExchange("BYBIT");
        request.setMode("sim");

        // when
        InvestmentHistoryPageResponse result = myPageService.getInvestmentHistories(userId, request);

        // then
        assertThat(result.getPage().getContent()).hasSize(1);
        assertThat(result.getSummary().getWinRate()).isEqualTo("100.0");
        then(mainHistory).shouldHaveNoInteractions();
    }

    // ─────────────────────────────────────────────
    // getInvestmentTradeDetails
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("getInvestmentTradeDetails: 최신순 거래에서 EXIT 1건 + 직전 ENTRY 1건만 ENTRY→EXIT 순으로 반환한다")
    void getInvestmentTradeDetails_extractsLatestCycle() {
        // given: DESC 순 — [SELL(현재 사이클), ENTRY(현재 사이클), LIQUIDATION(이전), ENTRY(이전)]
        Long userId = 1L;
        InvestmentHistoryDTO inv = makeInvestmentHistory(5L, userId, "BTCUSDT");
        given(mainHistory.findInvestment(5L, userId)).willReturn(Optional.of(inv));
        given(mainHistory.findTradesUpTo(1L, userId, inv.getCreatedAt())).willReturn(List.of(
                makeTrade(4L, "SELL"), makeTrade(3L, "ENTRY"), makeTrade(2L, "LIQUIDATION"), makeTrade(1L, "ENTRY")));

        // when
        List<InvestmentTradeDetailResponse> result = myPageService.getInvestmentTradeDetails(userId, 5L, "main");

        // then
        assertThat(result).extracting(InvestmentTradeDetailResponse::getId).containsExactly(3L, 4L);
        assertThat(result).extracting(InvestmentTradeDetailResponse::getOrderType).containsExactly("ENTRY", "SELL");
        assertThat(result).extracting(InvestmentTradeDetailResponse::getExchangeType).containsOnly("BYBIT");
    }

    @Test
    @DisplayName("getInvestmentTradeDetails: 투자 히스토리가 없거나 EXIT 거래가 없으면 빈 목록이다")
    void getInvestmentTradeDetails_emptyCases() {
        // given
        Long userId = 1L;
        given(mainHistory.findInvestment(7L, userId)).willReturn(Optional.empty());
        InvestmentHistoryDTO inv = makeInvestmentHistory(8L, userId, "BTCUSDT");
        given(simHistory.findInvestment(8L, userId)).willReturn(Optional.of(inv));
        given(simHistory.findTradesUpTo(1L, userId, inv.getCreatedAt())).willReturn(List.of(makeTrade(1L, "ENTRY")));

        // when & then
        assertThat(myPageService.getInvestmentTradeDetails(userId, 7L, "main")).isEmpty();
        assertThat(myPageService.getInvestmentTradeDetails(userId, 8L, "sim")).isEmpty();
    }

    // ─────────────────────────────────────────────
    // getHistoryChart
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("getHistoryChart: 심볼이 없으면 전체 승패 카운트를 사용하고 값을 문자열로 변환한다")
    void getHistoryChart_withoutSymbol() {
        // given
        Long userId = 1L;
        given(mainHistory.findDailyProfitLoss(eq(userId), eq(ExchangeType.BYBIT), isNull(), any(), any()))
                .willReturn(List.of(new DailyProfitLossDTO("2026-01-02", new BigDecimal("7.500"))));
        given(mainHistory.findProfitLossBySymbol(eq(userId), eq(ExchangeType.BYBIT), isNull(), any(), any()))
                .willReturn(List.of(new SymbolProfitLossDTO("BTCUSDT", new BigDecimal("7.5"))));
        given(mainHistory.countWinLoss(eq(userId), eq(ExchangeType.BYBIT), any(), any())).willReturn(new WinLossCountDTO(2, 1, 3));

        // when
        HistoryChartResponse result = myPageService.getHistoryChart(userId, makeInvestmentHistoryRequest());

        // then
        assertThat(result.getDailyData().get(0).getDate()).isEqualTo("2026-01-02");
        assertThat(result.getDailyData().get(0).getProfitLoss()).isEqualTo("7.5");
        assertThat(result.getSymbolData().get(0).getSymbol()).isEqualTo("BTCUSDT");
        assertThat(result.getWinCount()).isEqualTo(2);
        assertThat(result.getLossCount()).isEqualTo(1);
        assertThat(result.getTotalCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("getHistoryChart: 심볼이 있으면 심볼 기준 승패 카운트를 사용한다 (SIM 모드)")
    void getHistoryChart_withSymbol_sim() {
        // given
        Long userId = 1L;
        given(simHistory.findDailyProfitLoss(eq(userId), eq(ExchangeType.BYBIT), eq("ETH"), any(), any())).willReturn(List.of());
        given(simHistory.findProfitLossBySymbol(eq(userId), eq(ExchangeType.BYBIT), eq("ETH"), any(), any())).willReturn(List.of());
        given(simHistory.countWinLossBySymbol(eq(userId), eq(ExchangeType.BYBIT), eq("ETH"), any(), any())).willReturn(new WinLossCountDTO(1, 1, 2));

        InvestmentHistoryRequest request = new InvestmentHistoryRequest();
        request.setExchange("BYBIT");
        request.setSymbol("ETH");
        request.setMode("sim");

        // when
        HistoryChartResponse result = myPageService.getHistoryChart(userId, request);

        // then
        assertThat(result.getTotalCount()).isEqualTo(2);
        then(simHistory).should(never()).countWinLoss(any(), any(), any(), any());
    }

    // ─────────────────────────────────────────────
    // 삭제 — mode 에 맞는 HistoryService 로 위임
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("deleteTradeHistories: MAIN/SIM 모드에 맞는 히스토리 서비스로 선택 삭제를 위임한다")
    void deleteTradeHistories_delegatesByMode() {
        Long userId = 1L;

        myPageService.deleteTradeHistories(userId, List.of(1L, 2L), "main");
        myPageService.deleteTradeHistories(userId, List.of(3L), "sim");

        then(mainHistory).should().deleteTrades(List.of(1L, 2L), userId);
        then(simHistory).should().deleteTrades(List.of(3L), userId);
        then(mainHistory).should(never()).deleteTrades(eq(List.of(3L)), any());
    }

    @Test
    @DisplayName("deleteAllTradeHistories: 모드에 맞는 히스토리 서비스로 선택 거래소 전체 삭제를 위임한다")
    void deleteAllTradeHistories_delegatesByModeAndExchange() {
        myPageService.deleteAllTradeHistories(1L, "main", "bybit");
        myPageService.deleteAllTradeHistories(2L, "sim", "BYBIT");

        then(mainHistory).should().deleteAllTrades(1L, ExchangeType.BYBIT);
        then(simHistory).should().deleteAllTrades(2L, ExchangeType.BYBIT);
        then(mainHistory).should(never()).deleteAllTrades(eq(2L), any());
    }

    @Test
    @DisplayName("deleteAllTradeHistories: 거래소 미전달이면 기본값 없이 EXCHANGE_TYPE_REQUIRED, 삭제를 위임하지 않는다")
    void deleteAllTradeHistories_missingExchange() {
        assertThatThrownBy(() -> myPageService.deleteAllTradeHistories(2L, "sim", null))
                .isInstanceOf(CustomException.class)
                .hasMessage(ExceptionMessage.EXCHANGE_TYPE_REQUIRED.getMessage());

        then(simHistory).should(never()).deleteAllTrades(any(), any());
    }

    @Test
    @DisplayName("deleteInvestmentHistories: MAIN/SIM 모드에 맞는 히스토리 서비스로 선택 삭제를 위임한다")
    void deleteInvestmentHistories_delegatesByMode() {
        Long userId = 1L;

        myPageService.deleteInvestmentHistories(userId, List.of(10L, 20L), "main");
        myPageService.deleteInvestmentHistories(userId, List.of(30L), "sim");

        then(mainHistory).should().deleteInvestments(List.of(10L, 20L), userId);
        then(simHistory).should().deleteInvestments(List.of(30L), userId);
        then(mainHistory).should(never()).deleteInvestments(eq(List.of(30L)), any());
    }

    @Test
    @DisplayName("deleteAllInvestmentHistories: 모드에 맞는 히스토리 서비스로 선택 거래소 전체 삭제를 위임한다")
    void deleteAllInvestmentHistories_delegatesByModeAndExchange() {
        myPageService.deleteAllInvestmentHistories(1L, "main", "bybit");
        myPageService.deleteAllInvestmentHistories(2L, "sim", "BYBIT");

        then(mainHistory).should().deleteAllInvestments(1L, ExchangeType.BYBIT);
        then(simHistory).should().deleteAllInvestments(2L, ExchangeType.BYBIT);
        then(mainHistory).should(never()).deleteAllInvestments(eq(2L), any());
    }

    @Test
    @DisplayName("deleteAllInvestmentHistories: 거래소 미전달이면 기본값 없이 EXCHANGE_TYPE_REQUIRED, 삭제를 위임하지 않는다")
    void deleteAllInvestmentHistories_missingExchange() {
        assertThatThrownBy(() -> myPageService.deleteAllInvestmentHistories(2L, "sim", null))
                .isInstanceOf(CustomException.class)
                .hasMessage(ExceptionMessage.EXCHANGE_TYPE_REQUIRED.getMessage());

        then(simHistory).should(never()).deleteAllInvestments(any(), any());
    }

    @Test
    @DisplayName("deleteAll: 지원하지 않는 거래소면 삭제하지 않고 INVALID_EXCHANGE_TYPE 예외")
    void deleteAll_unknownExchange_rejected() {
        assertThatThrownBy(() -> myPageService.deleteAllInvestmentHistories(1L, "main", "nope"))
                .isInstanceOf(CustomException.class)
                .extracting("exceptionMessage").isEqualTo(ExceptionMessage.INVALID_EXCHANGE_TYPE);
        then(mainHistory).should(never()).deleteAllInvestments(any(), any());
    }

    // ─────────────────────────────────────────────
    // 헬퍼 메서드
    // ─────────────────────────────────────────────

    /**
     * PatternQueueDTO 생성 (withPatterns=true 이면 1단계 L:L 패턴 1개 포함)
     */
    private PatternQueueDTO makeQueueDto(Long id, String symbol, boolean withPatterns) {
        List<PatternQueueDTO.PatternDTO> patterns = withPatterns ? List.of(PatternQueueDTO.PatternDTO.builder()
                .stepLevel(1).patternOrder(1).amount(new BigDecimal("10")).leverage(5)
                .stopLossRate(new BigDecimal("1.0")).takeProfitRate(new BigDecimal("5.0"))
                .conditionBlocks(List.of(Side.LONG)).leafBlock(Side.LONG)
                .build()) : List.of();
        return PatternQueueDTO.builder()
                .id(id).symbol(symbol).exchangeType("BYBIT").active(false)
                .triggerRate(new BigDecimal("1.0"))
                .createdAt(LocalDateTime.of(2026, 1, 10, 12, 0))
                .patterns(patterns).build();
    }

    private InvestmentHistoryDTO makeInvestmentHistory(Long id, Long userId, String symbol) {
        return InvestmentHistoryDTO.builder()
                .id(id).userId(userId).patternId(1L).symbol(symbol).side(Side.LONG)
                .entryPrice(new BigDecimal("50000")).exitPrice(new BigDecimal("51000"))
                .amount(new BigDecimal("10000")).profitLoss(new BigDecimal("200"))
                .exchangeType(ExchangeType.BYBIT)
                .createdAt(LocalDateTime.of(2026, 1, 10, 12, 0))
                .build();
    }

    private TradeHistoryDTO makeTrade(Long id, String orderType) {
        return TradeHistoryDTO.builder()
                .id(id).patternId(1L).userId(1L).symbol("BTCUSDT").side(Side.LONG)
                .amount(new BigDecimal("100")).executedPrice(new BigDecimal("50000"))
                .orderType(orderType).orderResult(OrderResult.SUCCESS)
                .exchangeType(ExchangeType.BYBIT)
                .createdAt(LocalDateTime.of(2026, 1, 10, 12, 0))
                .build();
    }

    /** 거래소(BYBIT)만 지정한 기본 패턴 조회 요청 */
    private MyPagePatternRequest makeMyPagePatternRequest() {
        MyPagePatternRequest request = new MyPagePatternRequest();
        request.setExchange("BYBIT");
        return request;
    }

    /** 거래소(BYBIT)만 지정한 기본 투자 히스토리 조회 요청 */
    private InvestmentHistoryRequest makeInvestmentHistoryRequest() {
        InvestmentHistoryRequest request = new InvestmentHistoryRequest();
        request.setExchange("BYBIT");
        return request;
    }
}
