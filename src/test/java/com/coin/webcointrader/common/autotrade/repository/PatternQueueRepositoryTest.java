package com.coin.webcointrader.common.autotrade.repository;

import com.coin.webcointrader.common.util.OtherExchangeTestData;
import org.springframework.jdbc.core.JdbcTemplate;
import com.coin.webcointrader.common.config.JpaConfig;
import com.coin.webcointrader.common.autotrade.entity.Pattern;
import com.coin.webcointrader.common.autotrade.entity.PatternQueue;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.enums.Side;
import com.coin.webcointrader.common.enums.TradeMode;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DataJpaTest
@Import(JpaConfig.class)
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class PatternQueueRepositoryTest {

    @Autowired
    private PatternQueueRepository patternQueueRepository;

    @Autowired
    private TestEntityManager testEntityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private static final Long USER_ID = 1L;

    @AfterEach
    void tearDown() {
        patternQueueRepository.deleteAll();
    }

    // ─────────────────────────────────────────────
    // findByUserIdAndSymbolContainingIgnoreCaseAndTradeModeAndExchangeTypeAndCreatedAtBetween
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("symbol 키워드 + Pageable: DB 레벨에서 필터링·페이징된 결과만 반환한다")
    void findBySymbol_pagedAtDbLevel() {
        // given
        patternQueueRepository.save(makeQueue(USER_ID, "BTCUSDT"));
        patternQueueRepository.save(makeQueue(USER_ID, "ETHUSDT"));

        // when
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now().plusDays(1);
        Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<PatternQueue> result = patternQueueRepository
                .findByUserIdAndSymbolContainingIgnoreCaseAndTradeModeAndExchangeTypeAndCreatedAtBetween(
                        USER_ID, "BTC", TradeMode.MAIN, ExchangeType.BYBIT, start, end, pageable);

        // then
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getSymbol()).isEqualTo("BTCUSDT");
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    // ─────────────────────────────────────────────
    // 거래소 조건 (마이페이지 = 헤더 선택 거래소 기준)
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("거래소 조건: 다른 거래소 큐는 페이지 조회(심볼 유무 모두)에서 제외된다")
    void findQueuePage_filtersByExchange() {
        // given - BTCUSDT 는 BYBIT, ETHUSDT 는 다른 거래소(아직 enum 에 없는 값이라 SQL 로 지정, 제약 해제는 저장 전에)
        OtherExchangeTestData.allowAnyExchangeType(jdbcTemplate, "pattern_queue");
        patternQueueRepository.save(makeQueue(USER_ID, "BTCUSDT"));
        PatternQueue other = patternQueueRepository.save(makeQueue(USER_ID, "ETHUSDT"));
        setOtherExchange("pattern_queue", other.getId());

        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now().plusDays(1);
        Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"));

        // when
        Page<PatternQueue> all = patternQueueRepository.findByUserIdAndTradeModeAndExchangeTypeAndCreatedAtBetween(
                USER_ID, TradeMode.MAIN, ExchangeType.BYBIT, start, end, pageable);
        Page<PatternQueue> bySymbol = patternQueueRepository
                .findByUserIdAndSymbolContainingIgnoreCaseAndTradeModeAndExchangeTypeAndCreatedAtBetween(
                        USER_ID, "USDT", TradeMode.MAIN, ExchangeType.BYBIT, start, end, pageable);

        // then
        assertThat(all.getContent()).extracting(PatternQueue::getSymbol).containsExactly("BTCUSDT");
        assertThat(bySymbol.getContent()).extracting(PatternQueue::getSymbol).containsExactly("BTCUSDT");

        // tearDown 의 deleteAll 은 엔티티로 불러오므로 'OTHER' 행은 먼저 SQL 로 정리
        OtherExchangeTestData.deleteOtherExchangeRows(jdbcTemplate, "pattern_queue");
    }

    // ─────────────────────────────────────────────
    // 배치 페치 (N+1 해소) 검증
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("큐 페이지 조회 후 패턴·블록을 순회해도 쿼리 수가 일정 수준으로 유지된다")
    void hierarchyTraversal_keepsQueryCountBounded() {
        // given - 큐 3개 x 단계 2개 x 패턴 2개 (배치 페치 없으면 큐마다 패턴 조회 쿼리 발생)
        for (int i = 0; i < 3; i++) {
            patternQueueRepository.save(makeQueueWithHierarchy(USER_ID, "SYM" + i));
        }
        // 세션 캐시에 남아있는 엔티티를 비워 실제 재조회 시 지연로딩이 발생하도록 한다
        testEntityManager.flush();
        testEntityManager.clear();

        Statistics statistics = statistics();
        statistics.clear();

        // when
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now().plusDays(1);
        Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<PatternQueue> page = patternQueueRepository
                .findByUserIdAndTradeModeAndExchangeTypeAndCreatedAtBetween(
                        USER_ID, TradeMode.MAIN, ExchangeType.BYBIT, start, end, pageable);

        page.getContent().forEach(q -> q.getPatterns().forEach(p -> p.getBlocks().size()));

        // then - 블록은 pattern 컬럼에 함께 저장되므로 큐 조회(+count) + 패턴 배치 조회 1회 수준으로 고정
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(3);
    }

    // ─────────────────────────────────────────────
    // 패턴 블록 저장 형식 (pattern.blocks 컬럼)
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("패턴 블록 목록은 순서를 보존해 저장·재조회되고, 패턴은 단계·순서대로 정렬된다")
    void patterns_persistBlocksAndOrderByStep() {
        // given - 단계 2 → 단계 1 역순으로 추가해도 조회 시 stepLevel, patternOrder 순으로 정렬되어야 한다
        PatternQueue q = makeQueue(USER_ID, "BTCUSDT");
        q.getPatterns().add(makePattern(q, 2, 1, List.of(Side.SHORT, Side.LONG)));
        q.getPatterns().add(makePattern(q, 1, 2, List.of(Side.SHORT, Side.SHORT, Side.LONG)));
        q.getPatterns().add(makePattern(q, 1, 1, List.of(Side.LONG, Side.SHORT, Side.LONG)));
        Long queueId = patternQueueRepository.save(q).getId();
        testEntityManager.flush();
        testEntityManager.clear();

        // when
        PatternQueue found = patternQueueRepository.findById(queueId).orElseThrow();

        // then
        assertThat(found.getPatterns())
                .extracting(Pattern::getStepLevel, Pattern::getPatternOrder)
                .containsExactly(tuple(1, 1), tuple(1, 2), tuple(2, 1));
        assertThat(found.getPatterns().get(0).getBlocks())
                .containsExactly(Side.LONG, Side.SHORT, Side.LONG);
        assertThat(found.getPatterns().get(1).getBlocks())
                .containsExactly(Side.SHORT, Side.SHORT, Side.LONG);
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    // ─────────────────────────────────────────────
    // findByUserIdAndTradeModeOrderByCreatedAtAsc
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("거래 모드만 지정하면 거래소와 무관하게 사용자의 큐를 생성 순서대로 반환한다")
    void findByUserIdAndTradeMode_allExchanges() {
        // given: Bybit·Binance MAIN 큐 + 다른 모드(SIM) 큐 + 다른 사용자 큐
        patternQueueRepository.save(makeQueue(USER_ID, "BTCUSDT"));
        PatternQueue binance = makeQueue(USER_ID, "ETHUSDT");
        binance.setExchangeType(ExchangeType.BINANCE);
        patternQueueRepository.save(binance);
        PatternQueue sim = makeQueue(USER_ID, "SOLUSDT");
        sim.setTradeMode(TradeMode.SIM);
        patternQueueRepository.save(sim);
        patternQueueRepository.save(makeQueue(2L, "XRPUSDT"));

        // when
        List<PatternQueue> result = patternQueueRepository.findByUserIdAndTradeModeOrderByCreatedAtAsc(USER_ID, TradeMode.MAIN);

        // then
        assertThat(result).extracting(PatternQueue::getSymbol).containsExactly("BTCUSDT", "ETHUSDT");
        assertThat(result).extracting(PatternQueue::getExchangeType)
                .containsExactly(ExchangeType.BYBIT, ExchangeType.BINANCE);
    }

    // ─────────────────────────────────────────────
    // 헬퍼 메서드
    // ─────────────────────────────────────────────

    /** 행의 거래소를 enum 에 없는 다른 값으로 바꾼다. (두 번째 거래소 데이터 흉내) */
    private void setOtherExchange(String table, Long id) {
        testEntityManager.flush();
        OtherExchangeTestData.setOtherExchange(jdbcTemplate, table, id);
        testEntityManager.clear();
    }

    private PatternQueue makeQueue(Long userId, String symbol) {
        PatternQueue q = new PatternQueue();
        q.setExchangeType(ExchangeType.BYBIT);
        q.setUserId(userId);
        q.setSymbol(symbol);
        q.setTriggerRate(new BigDecimal("1.0"));
        return q;
    }

    /**
     * 큐 1개 + 단계 2개 x 단계당 패턴 2개(블록 2개씩) 구조 생성.
     */
    private PatternQueue makeQueueWithHierarchy(Long userId, String symbol) {
        PatternQueue q = makeQueue(userId, symbol);
        for (int s = 1; s <= 2; s++) {
            for (int p = 1; p <= 2; p++) {
                q.getPatterns().add(makePattern(q, s, p, List.of(Side.LONG, Side.LONG)));
            }
        }
        return q;
    }

    /**
     * cascade=ALL 저장을 위해 자식 → 부모 FK 필드(setQueue)를 직접 설정한 패턴 생성.
     */
    private Pattern makePattern(PatternQueue q, int stepLevel, int patternOrder, List<Side> blocks) {
        Pattern pattern = new Pattern();
        pattern.setQueue(q);
        pattern.setStepLevel(stepLevel);
        pattern.setPatternOrder(patternOrder);
        pattern.setAmount(new BigDecimal("10"));
        pattern.setLeverage(5);
        pattern.setBlocks(new ArrayList<>(blocks));
        return pattern;
    }
}
