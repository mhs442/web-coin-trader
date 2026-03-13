package com.coin.webcointrader.autotrade.repository;

import com.coin.webcointrader.common.config.JpaConfig;
import com.coin.webcointrader.common.entity.Side;
import com.coin.webcointrader.common.entity.TradeHistory;
import com.coin.webcointrader.common.enums.OrderResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DataJpaTest
@Import(JpaConfig.class)
class TradeHistoryRepositoryTest {

    @Autowired
    private TradeHistoryRepository tradeHistoryRepository;

    private static final Long USER_ID = 1L;
    private static final String SYMBOL = "BTCUSDT";

    @AfterEach
    void tearDown() {
        tradeHistoryRepository.deleteAll();
    }

    // ─────────────────────────────────────────────
    // findByUserId (Sort)
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("findByUserId: Sort를 적용하여 해당 사용자의 거래 히스토리만 반환한다")
    void findByUserId_withSort() {
        // given
        TradeHistory h1 = makeTradeHistory(USER_ID, SYMBOL);
        TradeHistory h2 = makeTradeHistory(USER_ID, SYMBOL);
        TradeHistory other = makeTradeHistory(2L, "ETHUSDT"); // 다른 사용자
        tradeHistoryRepository.saveAll(List.of(h1, h2, other));

        // when
        Sort sort = Sort.by(Sort.Direction.DESC, "createdAt");
        List<TradeHistory> result = tradeHistoryRepository.findByUserId(USER_ID, sort);

        // then
        assertThat(result).hasSize(2);
        assertThat(result).allMatch(h -> h.getUserId().equals(USER_ID));
    }

    // ─────────────────────────────────────────────
    // findByUserIdAndCreatedAtBetween (Sort)
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("findByUserIdAndCreatedAtBetween: 날짜 범위 내 거래 히스토리를 반환한다")
    void findByUserIdAndCreatedAtBetween_returnsInRange() {
        // given - createdAt은 @CreatedDate로 자동 설정(현재 시각)
        TradeHistory h = makeTradeHistory(USER_ID, SYMBOL);
        tradeHistoryRepository.save(h);

        // when - 현재 시각 기준 ±1일 범위 조회
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now().plusDays(1);
        Sort sort = Sort.by(Sort.Direction.DESC, "createdAt");
        List<TradeHistory> result = tradeHistoryRepository
                .findByUserIdAndCreatedAtBetween(USER_ID, start, end, sort);

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSymbol()).isEqualTo(SYMBOL);
    }

    @Test
    @DisplayName("findByUserIdAndCreatedAtBetween: 날짜 범위 밖이면 빈 리스트를 반환한다")
    void findByUserIdAndCreatedAtBetween_returnsEmptyWhenOutOfRange() {
        // given
        TradeHistory h = makeTradeHistory(USER_ID, SYMBOL);
        tradeHistoryRepository.save(h);

        // when - 미래 범위(데이터가 없는 구간)
        LocalDateTime start = LocalDateTime.now().plusDays(1);
        LocalDateTime end = LocalDateTime.now().plusDays(2);
        Sort sort = Sort.by(Sort.Direction.DESC, "createdAt");
        List<TradeHistory> result = tradeHistoryRepository
                .findByUserIdAndCreatedAtBetween(USER_ID, start, end, sort);

        // then
        assertThat(result).isEmpty();
    }

    // ─────────────────────────────────────────────
    // 헬퍼 메서드
    // ─────────────────────────────────────────────

    private TradeHistory makeTradeHistory(Long userId, String symbol) {
        TradeHistory h = new TradeHistory();
        h.setUserId(userId);
        h.setSymbol(symbol);
        h.setSide(Side.LONG);
        h.setQuantity(new BigDecimal("0.01"));
        h.setExecutedPrice(new BigDecimal("50000"));
        h.setOrderResult(OrderResult.SUCCESS);
        h.setQueueStepId(1L);
        return h;
    }
}
