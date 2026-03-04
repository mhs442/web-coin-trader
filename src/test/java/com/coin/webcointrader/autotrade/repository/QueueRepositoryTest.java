package com.coin.webcointrader.autotrade.repository;

import com.coin.webcointrader.config.JpaConfig;
import com.coin.webcointrader.common.entity.Queue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DataJpaTest
@Import(JpaConfig.class)
class QueueRepositoryTest {

    @Autowired
    private QueueRepository queueRepository;

    private static final Long USER_ID = 1L;
    private static final String SYMBOL = "BTCUSDT";

    @AfterEach
    void tearDown() {
        queueRepository.deleteAll();
    }

    // ─────────────────────────────────────────────
    // findByUserIdAndSymbolAndDelYnOrderBySortOrderAsc
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("findByUserIdAndSymbolAndDelYnOrderBySortOrderAsc: 삭제되지 않은 큐를 sortOrder 오름차순으로 반환한다")
    void findByUserIdAndSymbol_returnsNonDeletedInOrder() {
        // given
        Queue q1 = makeQueue(USER_ID, SYMBOL, 1, "Y", "N");
        Queue q2 = makeQueue(USER_ID, SYMBOL, 0, "Y", "N"); // sortOrder 낮음
        Queue deleted = makeQueue(USER_ID, SYMBOL, 2, "Y", "Y"); // 삭제됨
        queueRepository.saveAll(List.of(q1, q2, deleted));

        // when
        List<Queue> result = queueRepository
                .findByUserIdAndSymbolAndDelYnOrderBySortOrderAsc(USER_ID, SYMBOL, "N");

        // then
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getSortOrder()).isEqualTo(0);
        assertThat(result.get(1).getSortOrder()).isEqualTo(1);
    }

    // ─────────────────────────────────────────────
    // countByUserIdAndSymbolAndDelYn
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("countByUserIdAndSymbolAndDelYn: 삭제되지 않은 큐 개수만 반환한다")
    void countByUserIdAndSymbolAndDelYn_returnsCount() {
        // given
        Queue q1 = makeQueue(USER_ID, SYMBOL, 0, "Y", "N");
        Queue q2 = makeQueue(USER_ID, SYMBOL, 1, "Y", "N");
        Queue deleted = makeQueue(USER_ID, SYMBOL, 2, "Y", "Y");
        queueRepository.saveAll(List.of(q1, q2, deleted));

        // when
        long count = queueRepository.countByUserIdAndSymbolAndDelYn(USER_ID, SYMBOL, "N");

        // then
        assertThat(count).isEqualTo(2);
    }

    // ─────────────────────────────────────────────
    // findByUserIdAndSymbolAndUseYnAndDelYnOrderBySortOrderAsc
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("findByUserIdAndSymbolAndUseYnAndDelYnOrderBySortOrderAsc: 활성화된 큐만 반환한다")
    void findByUseYnAndDelYn_returnsOnlyActiveQueues() {
        // given
        Queue active = makeQueue(USER_ID, SYMBOL, 0, "Y", "N");
        Queue inactive = makeQueue(USER_ID, SYMBOL, 1, "N", "N"); // 비활성
        queueRepository.saveAll(List.of(active, inactive));

        // when
        List<Queue> result = queueRepository
                .findByUserIdAndSymbolAndUseYnAndDelYnOrderBySortOrderAsc(USER_ID, SYMBOL, "Y", "N");

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getUseYn()).isEqualTo("Y");
    }

    // ─────────────────────────────────────────────
    // findByUserIdAndDelYn (Sort)
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("findByUserIdAndDelYn: Sort를 적용하여 삭제되지 않은 전체 큐를 반환한다")
    void findByUserIdAndDelYn_withSort() {
        // given
        Queue q1 = makeQueue(USER_ID, SYMBOL, 1, "Y", "N");
        Queue q2 = makeQueue(USER_ID, SYMBOL, 0, "Y", "N");
        queueRepository.saveAll(List.of(q1, q2));

        // when
        Sort sort = Sort.by(Sort.Direction.ASC, "sortOrder");
        List<Queue> result = queueRepository.findByUserIdAndDelYn(USER_ID, "N", sort);

        // then
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getSortOrder()).isEqualTo(0);
        assertThat(result.get(1).getSortOrder()).isEqualTo(1);
    }

    // ─────────────────────────────────────────────
    // findByUserIdAndDelYnAndCreatedAtBetween (Sort)
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("findByUserIdAndDelYnAndCreatedAtBetween: 날짜 범위 내 큐만 반환한다")
    void findByCreatedAtBetween_returnsInRangeOnly() {
        // given
        Queue inRange = makeQueueWithCreatedAt(USER_ID, SYMBOL, 0, LocalDateTime.now());
        Queue outOfRange = makeQueueWithCreatedAt(USER_ID, SYMBOL, 1, LocalDateTime.now().minusDays(10));
        queueRepository.saveAll(List.of(inRange, outOfRange));

        // when
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now().plusDays(1);
        Sort sort = Sort.by(Sort.Direction.ASC, "sortOrder");
        List<Queue> result = queueRepository
                .findByUserIdAndDelYnAndCreatedAtBetween(USER_ID, "N", start, end, sort);

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSortOrder()).isEqualTo(0);
    }

    // ─────────────────────────────────────────────
    // 헬퍼 메서드
    // ─────────────────────────────────────────────

    private Queue makeQueue(Long userId, String symbol, int sortOrder, String useYn, String delYn) {
        Queue q = new Queue();
        q.setUserId(userId);
        q.setSymbol(symbol);
        q.setSortOrder(sortOrder);
        q.setUseYn(useYn);
        q.setDelYn(delYn);
        q.setCreatedAt(LocalDateTime.now());
        q.setSteps(new ArrayList<>());
        return q;
    }

    private Queue makeQueueWithCreatedAt(Long userId, String symbol, int sortOrder, LocalDateTime createdAt) {
        Queue q = new Queue();
        q.setUserId(userId);
        q.setSymbol(symbol);
        q.setSortOrder(sortOrder);
        q.setUseYn("Y");
        q.setDelYn("N");
        q.setCreatedAt(createdAt);
        q.setSteps(new ArrayList<>());
        return q;
    }
}
