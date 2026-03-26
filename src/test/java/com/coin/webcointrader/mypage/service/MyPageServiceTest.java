package com.coin.webcointrader.mypage.service;

import com.coin.webcointrader.autotrade.repository.InvestmentHistoryRepository;
import com.coin.webcointrader.autotrade.repository.PatternQueueRepository;
import com.coin.webcointrader.autotrade.repository.TradeHistoryRepository;
import com.coin.webcointrader.common.dto.response.PageResponse;
import com.coin.webcointrader.common.entity.*;
import com.coin.webcointrader.common.entity.Pattern;
import com.coin.webcointrader.common.enums.OrderResult;
import com.coin.webcointrader.common.enums.TradeOrderType;
import com.coin.webcointrader.mypage.dto.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class MyPageServiceTest {

    @InjectMocks
    private MyPageService myPageService;

    @Mock
    private PatternQueueRepository patternQueueRepository;

    @Mock
    private TradeHistoryRepository tradeHistoryRepository;

    @Mock
    private InvestmentHistoryRepository investmentHistoryRepository;

    // ─────────────────────────────────────────────
    // getPatterns
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("getPatterns: 기본 검색조건으로 패턴 큐를 페이징 조회한다")
    void getPatterns_defaultRequest() {
        // given
        Long userId = 1L;
        PatternQueue q = makePatternQueue(1L, userId, "BTCUSDT");
        Page<PatternQueue> page = new PageImpl<>(List.of(q));
        given(patternQueueRepository.findByUserIdAndCreatedAtBetween(
                eq(userId), any(), any(), any(Pageable.class)))
                .willReturn(page);

        MyPagePatternRequest request = new MyPagePatternRequest();

        // when
        PageResponse<MyPagePatternResponse> result = myPageService.getPatterns(userId, request);

        // then
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getSymbol()).isEqualTo("BTCUSDT");
    }

    @Test
    @DisplayName("getPatterns: symbol 키워드가 있으면 Java 필터 후 수동 페이징한다")
    void getPatterns_withSymbolFilter() {
        // given
        Long userId = 1L;
        PatternQueue btc = makePatternQueue(1L, userId, "BTCUSDT");
        PatternQueue eth = makePatternQueue(2L, userId, "ETHUSDT");
        given(patternQueueRepository.findByUserIdAndCreatedAtBetween(
                eq(userId), any(), any(), any(Sort.class)))
                .willReturn(List.of(btc, eth));

        MyPagePatternRequest request = new MyPagePatternRequest();
        request.setSymbol("BTC");

        // when
        PageResponse<MyPagePatternResponse> result = myPageService.getPatterns(userId, request);

        // then
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getSymbol()).isEqualTo("BTCUSDT");
    }

    @Test
    @DisplayName("getPatterns: 계층 구조(단계→패턴→블록)가 응답에 포함된다")
    void getPatterns_includesHierarchy() {
        // given
        Long userId = 1L;
        PatternQueue q = makePatternQueueWithSteps(1L, userId, "BTCUSDT");
        Page<PatternQueue> page = new PageImpl<>(List.of(q));
        given(patternQueueRepository.findByUserIdAndCreatedAtBetween(
                eq(userId), any(), any(), any(Pageable.class)))
                .willReturn(page);

        MyPagePatternRequest request = new MyPagePatternRequest();

        // when
        PageResponse<MyPagePatternResponse> result = myPageService.getPatterns(userId, request);

        // then
        MyPagePatternResponse response = result.getContent().get(0);
        // 단계 검증
        assertThat(response.getSteps()).hasSize(1);
        assertThat(response.getSteps().get(0).getStepLevel()).isEqualTo(1);
        // 패턴 검증
        assertThat(response.getSteps().get(0).getPatterns()).hasSize(1);
        assertThat(response.getSteps().get(0).getPatterns().get(0).getAmount())
                .isEqualByComparingTo(new BigDecimal("10"));
        // 블록 검증
        assertThat(response.getSteps().get(0).getPatterns().get(0).getBlocks()).hasSize(2);
    }

    // ─────────────────────────────────────────────
    // getTradeHistories
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("getTradeHistories: 기본 검색조건으로 거래 히스토리를 페이징 조회한다")
    void getTradeHistories_defaultRequest() {
        // given
        Long userId = 1L;
        TradeHistory history = makeTradeHistory(1L, userId, "BTCUSDT");
        Page<TradeHistory> page = new PageImpl<>(List.of(history));
        given(tradeHistoryRepository.findByUserIdAndCreatedAtBetween(
                eq(userId), any(), any(), any(Pageable.class)))
                .willReturn(page);

        TradeHistoryRequest request = new TradeHistoryRequest();

        // when
        PageResponse<TradeHistoryResponse> result = myPageService.getTradeHistories(userId, request);

        // then
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getSymbol()).isEqualTo("BTCUSDT");
    }

    @Test
    @DisplayName("getTradeHistories: symbol 키워드가 있으면 Java 필터 후 수동 페이징한다")
    void getTradeHistories_withSymbolFilter() {
        // given
        Long userId = 1L;
        TradeHistory btc = makeTradeHistory(1L, userId, "BTCUSDT");
        TradeHistory eth = makeTradeHistory(2L, userId, "ETHUSDT");
        given(tradeHistoryRepository.findByUserIdAndCreatedAtBetween(
                eq(userId), any(), any(), any(Sort.class)))
                .willReturn(List.of(btc, eth));

        TradeHistoryRequest request = new TradeHistoryRequest();
        request.setSymbol("ETH");
        request.setSort("asc");

        // when
        PageResponse<TradeHistoryResponse> result = myPageService.getTradeHistories(userId, request);

        // then
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getSymbol()).isEqualTo("ETHUSDT");
    }

    // ─────────────────────────────────────────────
    // getInvestmentHistories
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("getInvestmentHistories: 기본 검색조건으로 투자 히스토리와 합산 통계를 조회한다")
    void getInvestmentHistories_defaultRequest() {
        // given
        Long userId = 1L;
        InvestmentHistory history = makeInvestmentHistory(1L, userId, "BTCUSDT");
        Page<InvestmentHistory> page = new PageImpl<>(List.of(history));
        given(investmentHistoryRepository.findByUserIdAndCreatedAtBetween(
                eq(userId), any(), any(), any(Pageable.class)))
                .willReturn(page);
        // 합산 쿼리 모킹: 이익 500, 손해 -300
        given(investmentHistoryRepository.sumProfitLossByUserIdAndCreatedAtBetween(
                eq(userId), any(), any()))
                .willReturn(summaryResult(new BigDecimal("500"), new BigDecimal("-300")));

        InvestmentHistoryRequest request = new InvestmentHistoryRequest();

        // when
        InvestmentHistoryPageResponse result = myPageService.getInvestmentHistories(userId, request);

        // then - 페이징 데이터 검증
        assertThat(result.getPage().getContent()).hasSize(1);
        InvestmentHistoryResponse response = result.getPage().getContent().get(0);
        assertThat(response.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(response.getSide()).isEqualTo("LONG");
        assertThat(response.getEntryPrice()).isEqualTo("50000");
        assertThat(response.getExitPrice()).isEqualTo("51000");
        assertThat(response.getProfitLoss()).isEqualTo("200");

        // then - 합산 통계 검증
        assertThat(result.getSummary().getTotalProfit()).isEqualTo("500");
        assertThat(result.getSummary().getTotalLoss()).isEqualTo("-300");
        assertThat(result.getSummary().getNetTotal()).isEqualTo("200");
    }

    @Test
    @DisplayName("getInvestmentHistories: symbol 키워드가 있으면 Java 필터 후 수동 페이징 + 심볼별 합산한다")
    void getInvestmentHistories_withSymbolFilter() {
        // given
        Long userId = 1L;
        InvestmentHistory btc = makeInvestmentHistory(1L, userId, "BTCUSDT");
        InvestmentHistory eth = makeInvestmentHistory(2L, userId, "ETHUSDT");
        given(investmentHistoryRepository.findByUserIdAndCreatedAtBetween(
                eq(userId), any(), any(), any(Sort.class)))
                .willReturn(List.of(btc, eth));
        // 심볼 필터 합산 쿼리 모킹
        given(investmentHistoryRepository.sumProfitLossByUserIdAndCreatedAtBetweenAndSymbol(
                eq(userId), any(), any(), eq("ETH")))
                .willReturn(summaryResult(new BigDecimal("200"), BigDecimal.ZERO));

        InvestmentHistoryRequest request = new InvestmentHistoryRequest();
        request.setSymbol("ETH");

        // when
        InvestmentHistoryPageResponse result = myPageService.getInvestmentHistories(userId, request);

        // then - 페이징 데이터 검증
        assertThat(result.getPage().getContent()).hasSize(1);
        assertThat(result.getPage().getContent().get(0).getSymbol()).isEqualTo("ETHUSDT");

        // then - 합산 통계 검증
        assertThat(result.getSummary().getTotalProfit()).isEqualTo("200");
        assertThat(result.getSummary().getTotalLoss()).isEqualTo("0");
        assertThat(result.getSummary().getNetTotal()).isEqualTo("200");
    }

    @Test
    @DisplayName("getInvestmentHistories: 데이터가 없으면 합산 통계가 모두 0이다")
    void getInvestmentHistories_emptyReturnsZeroSummary() {
        // given
        Long userId = 1L;
        Page<InvestmentHistory> emptyPage = new PageImpl<>(List.of());
        given(investmentHistoryRepository.findByUserIdAndCreatedAtBetween(
                eq(userId), any(), any(), any(Pageable.class)))
                .willReturn(emptyPage);
        given(investmentHistoryRepository.sumProfitLossByUserIdAndCreatedAtBetween(
                eq(userId), any(), any()))
                .willReturn(summaryResult(BigDecimal.ZERO, BigDecimal.ZERO));

        InvestmentHistoryRequest request = new InvestmentHistoryRequest();

        // when
        InvestmentHistoryPageResponse result = myPageService.getInvestmentHistories(userId, request);

        // then
        assertThat(result.getPage().getContent()).isEmpty();
        assertThat(result.getSummary().getTotalProfit()).isEqualTo("0");
        assertThat(result.getSummary().getTotalLoss()).isEqualTo("0");
        assertThat(result.getSummary().getNetTotal()).isEqualTo("0");
    }

    // ─────────────────────────────────────────────
    // 헬퍼 메서드
    // ─────────────────────────────────────────────

    /**
     * 기본 PatternQueue 생성 (하위 엔티티 없음)
     */
    private PatternQueue makePatternQueue(Long id, Long userId, String symbol) {
        PatternQueue q = new PatternQueue();
        q.setId(id);
        q.setUserId(userId);
        q.setSymbol(symbol);
        q.setActive(false);
        q.setTriggerRate(new BigDecimal("1.0"));
        q.setFull(false);
        ReflectionTestUtils.setField(q, "createdAt", LocalDateTime.now());
        return q;
    }

    /**
     * 계층 구조 포함 PatternQueue 생성 (1단계, 1패턴, 2블록)
     */
    private PatternQueue makePatternQueueWithSteps(Long id, Long userId, String symbol) {
        PatternQueue q = makePatternQueue(id, userId, symbol);

        // 블록 생성
        PatternBlock condBlock = new PatternBlock();
        condBlock.setSide(Side.LONG);
        condBlock.setBlockOrder(1);
        condBlock.setLeaf(false);

        PatternBlock leafBlock = new PatternBlock();
        leafBlock.setSide(Side.LONG);
        leafBlock.setBlockOrder(2);
        leafBlock.setLeaf(true);

        // 패턴 생성
        Pattern pattern = new Pattern();
        pattern.setPatternOrder(1);
        pattern.setAmount(new BigDecimal("10"));
        pattern.setLeverage(5);
        pattern.setStopLossRate(new BigDecimal("1.0"));
        pattern.setTakeProfitRate(new BigDecimal("5.0"));
        pattern.getBlocks().add(condBlock);
        pattern.getBlocks().add(leafBlock);

        // 단계 생성
        PatternStep step = new PatternStep();
        step.setStepLevel(1);
        step.setFull(false);
        step.getPatterns().add(pattern);

        q.getSteps().add(step);
        return q;
    }

    /**
     * SUM 쿼리 결과 모킹용 헬퍼: List<Object[]> 형태로 반환
     */
    private List<Object[]> summaryResult(BigDecimal profit, BigDecimal loss) {
        List<Object[]> result = new ArrayList<>();
        result.add(new Object[]{profit, loss});
        return result;
    }

    private InvestmentHistory makeInvestmentHistory(Long id, Long userId, String symbol) {
        InvestmentHistory h = new InvestmentHistory();
        h.setId(id);
        h.setUserId(userId);
        h.setPatternStepId(1L);
        h.setSymbol(symbol);
        h.setSide(Side.LONG);
        h.setEntryPrice(new BigDecimal("50000"));
        h.setExitPrice(new BigDecimal("51000"));
        h.setAmount(new BigDecimal("10000"));
        h.setProfitLoss(new BigDecimal("200"));
        ReflectionTestUtils.setField(h, "createdAt", LocalDateTime.now());
        return h;
    }

    private TradeHistory makeTradeHistory(Long id, Long userId, String symbol) {
        TradeHistory h = new TradeHistory();
        h.setId(id);
        h.setUserId(userId);
        h.setSymbol(symbol);
        h.setSide(Side.LONG);
        h.setAmount(new BigDecimal("100"));
        h.setExecutedPrice(new BigDecimal("50000"));
        h.setOrderType(TradeOrderType.ENTRY.getTradeOrderType());
        h.setOrderResult(OrderResult.SUCCESS);
        h.setQueueStepId(1L);
        ReflectionTestUtils.setField(h, "createdAt", LocalDateTime.now());
        return h;
    }
}
