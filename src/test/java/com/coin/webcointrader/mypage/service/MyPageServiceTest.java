package com.coin.webcointrader.mypage.service;

import com.coin.webcointrader.autotrade.repository.QueueRepository;
import com.coin.webcointrader.autotrade.repository.TradeHistoryRepository;
import com.coin.webcointrader.common.dto.response.PageResponse;
import com.coin.webcointrader.common.entity.Queue;
import com.coin.webcointrader.common.entity.Side;
import com.coin.webcointrader.common.entity.TradeHistory;
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
    private QueueRepository queueRepository;

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
        Queue q = makeQueue(1L, userId, "BTCUSDT");
        Page<Queue> page = new PageImpl<>(List.of(q));
        given(queueRepository.findByUserIdAndDelYn(eq(userId), eq("N"), any(Pageable.class)))
                .willReturn(page);

        // when
        PageResponse<MyPagePatternResponse> result = myPageService.getPatterns(userId, null, null, null, "desc", 0, 20);

        // then
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getSymbol()).isEqualTo("BTCUSDT");
    }

    @Test
    @DisplayName("getPatterns: 날짜 범위가 있으면 날짜 범위로 페이징 조회한다")
    void getPatterns_withDateRange() {
        // given
        Long userId = 1L;
        Queue q = makeQueue(1L, userId, "BTCUSDT");
        Page<Queue> page = new PageImpl<>(List.of(q));
        given(queueRepository.findByUserIdAndDelYnAndCreatedAtBetween(
                eq(userId), eq("N"), any(), any(), any(Pageable.class)))
                .willReturn(page);

        // when
        PageResponse<MyPagePatternResponse> result = myPageService.getPatterns(
                userId, null, "2024-01-01", "2024-12-31", "desc", 0, 20);

        // then
        assertThat(result.getContent()).hasSize(1);
        then(queueRepository).should().findByUserIdAndDelYnAndCreatedAtBetween(
                eq(userId), eq("N"), any(), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("getPatterns: symbol 키워드가 있으면 Java 필터 후 수동 페이징한다")
    void getPatterns_withSymbolFilter() {
        // given
        Long userId = 1L;
        Queue btc = makeQueue(1L, userId, "BTCUSDT");
        Queue eth = makeQueue(2L, userId, "ETHUSDT");
        given(queueRepository.findByUserIdAndDelYn(eq(userId), eq("N"), any(Sort.class)))
                .willReturn(List.of(btc, eth));

        // when
        PageResponse<MyPagePatternResponse> result = myPageService.getPatterns(userId, "BTC", null, null, "desc", 0, 20);

        // then
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getSymbol()).isEqualTo("BTCUSDT");
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

    private Queue makeQueue(Long id, Long userId, String symbol) {
        Queue q = new Queue();
        q.setId(id);
        q.setUserId(userId);
        q.setSymbol(symbol);
        q.setSortOrder(0);
        q.setUseYn("Y");
        q.setDelYn("N");
        q.setCreatedAt(LocalDateTime.now());
        q.setSteps(new ArrayList<>());
        return q;
    }

    private TradeHistory makeTradeHistory(Long id, Long userId, String symbol) {
        TradeHistory h = new TradeHistory();
        h.setId(id);
        h.setUserId(userId);
        h.setSymbol(symbol);
        h.setSide(Side.LONG);
        h.setQuantity(new BigDecimal("0.01"));
        h.setExecutedPrice(new BigDecimal("50000"));
        h.setOrderResult(OrderResult.SUCCESS);
        h.setQueueStepId(1L);
        // createdAt은 BaseEntity @CreatedDate로 자동 설정되나, 테스트에서는 직접 주입
        ReflectionTestUtils.setField(h, "createdAt", LocalDateTime.now());
        return h;
    }
}
