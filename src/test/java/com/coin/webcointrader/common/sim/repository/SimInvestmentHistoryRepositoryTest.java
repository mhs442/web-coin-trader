package com.coin.webcointrader.common.sim.repository;

import com.coin.webcointrader.common.util.OtherExchangeTestData;
import org.springframework.jdbc.core.JdbcTemplate;
import com.coin.webcointrader.common.config.JpaConfig;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.enums.Side;
import com.coin.webcointrader.common.sim.entity.SimInvestmentHistory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * 모의 투자 히스토리 리포지토리 거래소 조건 통합 테스트.
 * 마이페이지는 헤더에서 선택한 거래소 기준으로 조회·집계·전체 삭제하므로,
 * 다른 거래소 데이터가 섞여 있어도 선택한 거래소 데이터만 대상이 되는지 검증한다.
 * (일별 손익 쿼리는 MySQL DATE_FORMAT 을 써서 H2 에서 검증하지 않음)
 */
@DataJpaTest
@Import(JpaConfig.class)
class SimInvestmentHistoryRepositoryTest {

    @Autowired
    private SimInvestmentHistoryRepository repository;

    @Autowired
    private TestEntityManager testEntityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final Long USER_ID = 1L;
    private static final ExchangeType BYBIT = ExchangeType.BYBIT;

    private LocalDateTime start;
    private LocalDateTime end;

    @BeforeEach
    void setUp() {
        // given - BYBIT 수익 10, 손실 -3 / 다른 거래소 수익 100 (모두 BTCUSDT)
        OtherExchangeTestData.allowAnyExchangeType(jdbcTemplate, "sim_investment_history"); // 제약 해제는 저장 전에 (H2 DDL 자동 커밋)
        repository.save(make("BTCUSDT", "10"));
        repository.save(make("BTCUSDT", "-3"));
        SimInvestmentHistory other = repository.save(make("BTCUSDT", "100"));
        setOtherExchange(other.getId());

        start = LocalDateTime.now().minusDays(1);
        end = LocalDateTime.now().plusDays(1);
    }

    @Test
    @DisplayName("페이지 조회: 심볼 유무와 관계없이 선택한 거래소 데이터만 반환한다")
    void findPage_filtersByExchange() {
        assertThat(repository.findByUserIdAndExchangeTypeAndCreatedAtBetween(
                USER_ID, BYBIT, start, end, PageRequest.of(0, 10)).getTotalElements()).isEqualTo(2);
        assertThat(repository.findByUserIdAndExchangeTypeAndSymbolContainingIgnoreCaseAndCreatedAtBetween(
                USER_ID, BYBIT, "btc", start, end, PageRequest.of(0, 10)).getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("손익 합계: 다른 거래소 손익은 합산하지 않는다")
    void sumProfitLoss_filtersByExchange() {
        assertSum(repository.sumProfitLossByUserIdAndCreatedAtBetween(USER_ID, BYBIT, start, end));
        assertSum(repository.sumProfitLossByUserIdAndCreatedAtBetweenAndSymbol(USER_ID, BYBIT, start, end, "BTC"));
    }

    @Test
    @DisplayName("승패 카운트: 다른 거래소 건수는 세지 않는다")
    void countWinLoss_filtersByExchange() {
        assertWinLoss(repository.countWinLoss(USER_ID, BYBIT, start, end));
        assertWinLoss(repository.countWinLossBySymbol(USER_ID, BYBIT, start, end, "BTC"));
    }

    @Test
    @DisplayName("심볼별 손익: 다른 거래소 손익은 합산하지 않는다")
    void profitLossBySymbol_filtersByExchange() {
        assertSymbolSum(repository.findProfitLossBySymbol(USER_ID, BYBIT, start, end));
        assertSymbolSum(repository.findProfitLossBySymbolFiltered(USER_ID, BYBIT, start, end, "BTC"));
    }

    @Test
    @DisplayName("전체 삭제: 선택한 거래소 데이터만 지우고 다른 거래소 데이터는 남긴다")
    void deleteAll_onlySelectedExchange() {
        repository.deleteAllByUserIdAndExchangeType(USER_ID, BYBIT);
        testEntityManager.flush();

        // 남은 1건은 다른 거래소 행 (enum 매핑 불가 값이라 건수로만 확인)
        assertThat(repository.count()).isEqualTo(1);
    }

    // ─────────────────────────────────────────────
    // 헬퍼 메서드
    // ─────────────────────────────────────────────

    /** 수익 합 10, 손실 합 -3 */
    private void assertSum(List<Object[]> rows) {
        assertThat((BigDecimal) rows.get(0)[0]).isEqualByComparingTo("10");
        assertThat((BigDecimal) rows.get(0)[1]).isEqualByComparingTo("-3");
        // 투입 금액(마진) 합: 선택 거래소 2건 × 100 (다른 거래소 100은 제외)
        assertThat((BigDecimal) rows.get(0)[2]).isEqualByComparingTo("200");
    }

    /** 승 1, 패 1, 전체 2 */
    private void assertWinLoss(List<Object[]> rows) {
        assertThat(((Number) rows.get(0)[0]).longValue()).isEqualTo(1);
        assertThat(((Number) rows.get(0)[1]).longValue()).isEqualTo(1);
        assertThat(((Number) rows.get(0)[2]).longValue()).isEqualTo(2);
    }

    /** BTCUSDT 합계 7 */
    private void assertSymbolSum(List<Object[]> rows) {
        assertThat(rows).hasSize(1);
        assertThat((BigDecimal) rows.get(0)[1]).isEqualByComparingTo("7");
    }

    /** 행의 거래소를 enum 에 없는 다른 값으로 바꾼다. (두 번째 거래소 데이터 흉내) */
    private void setOtherExchange(Long id) {
        testEntityManager.flush();
        OtherExchangeTestData.setOtherExchange(jdbcTemplate, "sim_investment_history", id);
        testEntityManager.clear();
    }

    private SimInvestmentHistory make(String symbol, String profitLoss) {
        SimInvestmentHistory h = new SimInvestmentHistory();
        h.setExchangeType(ExchangeType.BYBIT);
        h.setUserId(USER_ID);
        h.setPatternId(1L);
        h.setSymbol(symbol);
        h.setSide(Side.LONG);
        h.setProfitLoss(new BigDecimal(profitLoss));
        h.setEntryPrice(new BigDecimal("50000"));
        h.setExitPrice(new BigDecimal("51000"));
        h.setAmount(new BigDecimal("100"));
        h.setLeverage(1);
        return h;
    }
}
