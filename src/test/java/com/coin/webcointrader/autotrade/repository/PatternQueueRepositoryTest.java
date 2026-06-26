package com.coin.webcointrader.autotrade.repository;

import com.coin.webcointrader.common.config.JpaConfig;
import com.coin.webcointrader.common.entity.Pattern;
import com.coin.webcointrader.common.entity.PatternBlock;
import com.coin.webcointrader.common.entity.PatternQueue;
import com.coin.webcointrader.common.entity.PatternStep;
import com.coin.webcointrader.common.entity.Side;
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
    private EntityManagerFactory entityManagerFactory;

    private static final Long USER_ID = 1L;

    @AfterEach
    void tearDown() {
        patternQueueRepository.deleteAll();
    }

    // ─────────────────────────────────────────────
    // findByUserIdAndSymbolContainingIgnoreCaseAndTradeModeAndCreatedAtBetween
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
                .findByUserIdAndSymbolContainingIgnoreCaseAndTradeModeAndCreatedAtBetween(
                        USER_ID, "BTC", TradeMode.MAIN, start, end, pageable);

        // then
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getSymbol()).isEqualTo("BTCUSDT");
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    // ─────────────────────────────────────────────
    // 배치 페치 (N+1 해소) 검증
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("큐 페이지 조회 후 단계→패턴→블록 계층을 순회해도 쿼리 수가 일정 수준으로 유지된다")
    void hierarchyTraversal_keepsQueryCountBounded() {
        // given - 큐 3개 x 단계 2개 x 패턴 2개 x 블록 2개 (배치 페치 없으면 쿼리가 N+1로 폭증)
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
                .findByUserIdAndTradeModeAndCreatedAtBetween(USER_ID, TradeMode.MAIN, start, end, pageable);

        page.getContent().forEach(q -> q.getSteps().forEach(s ->
                s.getPatterns().forEach(p -> p.getBlocks().size())));

        // then - 배치 페치 미적용 시 큐3(단계)+단계6(패턴)+패턴12(블록) 등 20개 이상 쿼리 발생, 적용 시 레벨당 1쿼리 수준으로 고정
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(6);
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    // ─────────────────────────────────────────────
    // 헬퍼 메서드
    // ─────────────────────────────────────────────

    private PatternQueue makeQueue(Long userId, String symbol) {
        PatternQueue q = new PatternQueue();
        q.setUserId(userId);
        q.setSymbol(symbol);
        q.setTriggerRate(new BigDecimal("1.0"));
        return q;
    }

    /**
     * 큐 1개 + 단계 2개 + 단계당 패턴 2개 + 패턴당 블록 2개 계층 구조 생성.
     * cascade=ALL 저장을 위해 자식 → 부모 FK 필드(setQueue/setStep/setPattern)를 직접 설정한다.
     */
    private PatternQueue makeQueueWithHierarchy(Long userId, String symbol) {
        PatternQueue q = makeQueue(userId, symbol);
        for (int s = 1; s <= 2; s++) {
            PatternStep step = new PatternStep();
            step.setQueue(q);
            step.setStepLevel(s);
            for (int p = 1; p <= 2; p++) {
                Pattern pattern = new Pattern();
                pattern.setStep(step);
                pattern.setPatternOrder(p);
                pattern.setAmount(new BigDecimal("10"));
                pattern.setLeverage(5);
                for (int b = 1; b <= 2; b++) {
                    PatternBlock block = new PatternBlock();
                    block.setPattern(pattern);
                    block.setSide(Side.LONG);
                    block.setBlockOrder(b);
                    block.setLeaf(b == 2);
                    pattern.getBlocks().add(block);
                }
                step.getPatterns().add(pattern);
            }
            q.getSteps().add(step);
        }
        return q;
    }
}
