package com.coin.webcointrader.mypage.service;

import com.coin.webcointrader.autotrade.repository.PatternQueueRepository;
import com.coin.webcointrader.autotrade.repository.TradeHistoryRepository;
import com.coin.webcointrader.common.dto.response.PageResponse;
import com.coin.webcointrader.common.entity.*;
import com.coin.webcointrader.common.entity.Pattern;
import com.coin.webcointrader.common.enums.OrderResult;
import com.coin.webcointrader.mypage.dto.MyPagePatternResponse;
import com.coin.webcointrader.mypage.dto.TradeHistoryResponse;
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

    // ─────────────────────────────────────────────
    // getPatterns
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("getPatterns: 날짜 범위가 없으면 전체 큐를 페이징 조회한다")
    void getPatterns_noDateRange() {
        // given
        Long userId = 1L;
        PatternQueue q = makePatternQueue(1L, userId, "BTCUSDT");
        Page<PatternQueue> page = new PageImpl<>(List.of(q));
        given(patternQueueRepository.findByUserId(eq(userId), any(Pageable.class)))
                .willReturn(page);

        // when
        PageResponse<MyPagePatternResponse> result = myPageService.getPatterns(userId, null, null, null, "desc", 0, 20);

        // then
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getSymbol()).isEqualTo("BTCUSDT");
        assertThat(result.getContent().get(0).getTriggerSeconds()).isEqualTo(60);
    }

    @Test
    @DisplayName("getPatterns: 날짜 범위가 있으면 날짜 범위로 페이징 조회한다")
    void getPatterns_withDateRange() {
        // given
        Long userId = 1L;
        PatternQueue q = makePatternQueue(1L, userId, "BTCUSDT");
        Page<PatternQueue> page = new PageImpl<>(List.of(q));
        given(patternQueueRepository.findByUserIdAndCreatedAtBetween(
                eq(userId), any(), any(), any(Pageable.class)))
                .willReturn(page);

        // when
        PageResponse<MyPagePatternResponse> result = myPageService.getPatterns(
                userId, null, "2024-01-01", "2024-12-31", "desc", 0, 20);

        // then
        assertThat(result.getContent()).hasSize(1);
        then(patternQueueRepository).should().findByUserIdAndCreatedAtBetween(
                eq(userId), any(), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("getPatterns: symbol 키워드가 있으면 Java 필터 후 수동 페이징한다")
    void getPatterns_withSymbolFilter() {
        // given
        Long userId = 1L;
        PatternQueue btc = makePatternQueue(1L, userId, "BTCUSDT");
        PatternQueue eth = makePatternQueue(2L, userId, "ETHUSDT");
        given(patternQueueRepository.findByUserId(eq(userId), any(Sort.class)))
                .willReturn(List.of(btc, eth));

        // when
        PageResponse<MyPagePatternResponse> result = myPageService.getPatterns(userId, "BTC", null, null, "desc", 0, 20);

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
        given(patternQueueRepository.findByUserId(eq(userId), any(Pageable.class)))
                .willReturn(page);

        // when
        PageResponse<MyPagePatternResponse> result = myPageService.getPatterns(userId, null, null, null, "desc", 0, 20);

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
    @DisplayName("getTradeHistories: 날짜 범위가 없으면 전체 거래 히스토리를 페이징 조회한다")
    void getTradeHistories_noDateRange() {
        // given
        Long userId = 1L;
        TradeHistory history = makeTradeHistory(1L, userId, "BTCUSDT");
        Page<TradeHistory> page = new PageImpl<>(List.of(history));
        given(tradeHistoryRepository.findByUserId(eq(userId), any(Pageable.class)))
                .willReturn(page);

        // when
        PageResponse<TradeHistoryResponse> result = myPageService.getTradeHistories(userId, null, null, null, "desc", 0, 20);

        // then
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getSymbol()).isEqualTo("BTCUSDT");
    }

    @Test
    @DisplayName("getTradeHistories: 날짜 범위가 있으면 날짜 범위로 페이징 조회한다")
    void getTradeHistories_withDateRange() {
        // given
        Long userId = 1L;
        TradeHistory history = makeTradeHistory(1L, userId, "BTCUSDT");
        Page<TradeHistory> page = new PageImpl<>(List.of(history));
        given(tradeHistoryRepository.findByUserIdAndCreatedAtBetween(
                eq(userId), any(), any(), any(Pageable.class)))
                .willReturn(page);

        // when
        PageResponse<TradeHistoryResponse> result = myPageService.getTradeHistories(
                userId, null, "2024-01-01", "2024-12-31", "desc", 0, 20);

        // then
        assertThat(result.getContent()).hasSize(1);
        then(tradeHistoryRepository).should().findByUserIdAndCreatedAtBetween(
                eq(userId), any(), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("getTradeHistories: symbol 키워드가 있으면 Java 필터 후 수동 페이징한다")
    void getTradeHistories_withSymbolFilter() {
        // given
        Long userId = 1L;
        TradeHistory btc = makeTradeHistory(1L, userId, "BTCUSDT");
        TradeHistory eth = makeTradeHistory(2L, userId, "ETHUSDT");
        given(tradeHistoryRepository.findByUserId(eq(userId), any(Sort.class)))
                .willReturn(List.of(btc, eth));

        // when
        PageResponse<TradeHistoryResponse> result = myPageService.getTradeHistories(userId, "ETH", null, null, "asc", 0, 20);

        // then
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getSymbol()).isEqualTo("ETHUSDT");
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
        q.setTriggerSeconds(60);
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

    private TradeHistory makeTradeHistory(Long id, Long userId, String symbol) {
        TradeHistory h = new TradeHistory();
        h.setId(id);
        h.setUserId(userId);
        h.setSymbol(symbol);
        h.setSide(Side.LONG);
        h.setAmount(new BigDecimal("100"));
        h.setExecutedPrice(new BigDecimal("50000"));
        h.setOrderResult(OrderResult.SUCCESS);
        h.setQueueStepId(1L);
        ReflectionTestUtils.setField(h, "createdAt", LocalDateTime.now());
        return h;
    }
}
