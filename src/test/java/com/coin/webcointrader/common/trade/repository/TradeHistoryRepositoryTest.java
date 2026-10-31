package com.coin.webcointrader.common.trade.repository;

import com.coin.webcointrader.common.util.OtherExchangeTestData;
import org.springframework.jdbc.core.JdbcTemplate;
import com.coin.webcointrader.common.config.JpaConfig;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.enums.Side;
import com.coin.webcointrader.common.trade.entity.TradeHistory;
import com.coin.webcointrader.common.enums.OrderResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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

    @Autowired
    private TestEntityManager testEntityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final Long USER_ID = 1L;
    private static final String SYMBOL = "BTCUSDT";

    @AfterEach
    void tearDown() {
        tradeHistoryRepository.deleteAll();
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
    // deleteAllByUserIdAndExchangeType (마이페이지 전체 삭제 = 선택 거래소만)
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("deleteAllByUserIdAndExchangeType: 선택한 거래소 거래만 지우고 다른 거래소 거래는 남긴다")
    void deleteAll_onlySelectedExchange() {
        // given - BYBIT 1건 + 다른 거래소 1건 (enum 에 없는 값이라 SQL 로 지정, 제약 해제는 저장 전에)
        OtherExchangeTestData.allowAnyExchangeType(jdbcTemplate, "trade_history");
        tradeHistoryRepository.save(makeTradeHistory(USER_ID, SYMBOL));
        TradeHistory other = tradeHistoryRepository.save(makeTradeHistory(USER_ID, SYMBOL));
        testEntityManager.flush();
        OtherExchangeTestData.setOtherExchange(jdbcTemplate, "trade_history", other.getId());
        testEntityManager.clear();

        // when
        tradeHistoryRepository.deleteAllByUserIdAndExchangeType(USER_ID, ExchangeType.BYBIT);
        testEntityManager.flush();

        // then - 다른 거래소 1건만 남음
        assertThat(tradeHistoryRepository.count()).isEqualTo(1);

        // tearDown 의 deleteAll 은 엔티티로 불러오므로 'OTHER' 행은 먼저 SQL 로 정리
        OtherExchangeTestData.deleteOtherExchangeRows(jdbcTemplate, "trade_history");
    }

    // ─────────────────────────────────────────────
    // findFailedOrStopped (마이페이지 실패·중지 기록)
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("findFailedOrStopped: 사용자·거래소의 실패 거래와 중지 기록만 반환한다")
    void findFailedOrStopped_returnsOnlyFailedAndStop() {
        // given - 성공 진입(제외), 실패 진입, 중지 기록, 다른 사용자 실패(제외), 다른 거래소 실패(제외)
        tradeHistoryRepository.save(make(USER_ID, SYMBOL, "entry", OrderResult.SUCCESS, ExchangeType.BYBIT));
        TradeHistory failed = tradeHistoryRepository.save(make(USER_ID, SYMBOL, "entry", OrderResult.FAILED, ExchangeType.BYBIT));
        TradeHistory stop = tradeHistoryRepository.save(make(USER_ID, SYMBOL, "stop", OrderResult.SUCCESS, ExchangeType.BYBIT));
        tradeHistoryRepository.save(make(2L, SYMBOL, "entry", OrderResult.FAILED, ExchangeType.BYBIT));
        tradeHistoryRepository.save(make(USER_ID, SYMBOL, "entry", OrderResult.FAILED, ExchangeType.BINANCE));

        // when
        Page<TradeHistory> result = tradeHistoryRepository.findFailedOrStopped(
                USER_ID, ExchangeType.BYBIT, null, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1),
                PageRequest.of(0, 20));

        // then
        assertThat(result.getContent()).extracting(TradeHistory::getId)
                .containsExactlyInAnyOrder(failed.getId(), stop.getId());
    }

    @Test
    @DisplayName("findFailedOrStopped: 심볼 키워드는 대소문자 구분 없이 부분일치로 거른다")
    void findFailedOrStopped_filtersBySymbolKeyword() {
        // given
        TradeHistory btc = tradeHistoryRepository.save(make(USER_ID, "BTCUSDT", "entry", OrderResult.FAILED, ExchangeType.BYBIT));
        tradeHistoryRepository.save(make(USER_ID, "ETHUSDT", "entry", OrderResult.FAILED, ExchangeType.BYBIT));

        // when
        Page<TradeHistory> result = tradeHistoryRepository.findFailedOrStopped(
                USER_ID, ExchangeType.BYBIT, "btc", LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1),
                PageRequest.of(0, 20));

        // then
        assertThat(result.getContent()).extracting(TradeHistory::getId).containsExactly(btc.getId());
    }

    @Test
    @DisplayName("findFailedOrStopped: 기간 밖의 기록은 반환하지 않는다")
    void findFailedOrStopped_excludesOutOfRange() {
        // given
        tradeHistoryRepository.save(make(USER_ID, SYMBOL, "entry", OrderResult.FAILED, ExchangeType.BYBIT));

        // when - 미래 구간
        Page<TradeHistory> result = tradeHistoryRepository.findFailedOrStopped(
                USER_ID, ExchangeType.BYBIT, null, LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(2),
                PageRequest.of(0, 20));

        // then
        assertThat(result.getContent()).isEmpty();
    }

    // ─────────────────────────────────────────────
    // deleteFailedOrStoppedByIdInAndUserId (마이페이지 실패·중지 기록 선택 삭제)
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("deleteFailedOrStoppedByIdInAndUserId: 본인의 실패·중지 기록만 지우고 성공 거래·타인 기록은 남긴다")
    void deleteFailedOrStopped_onlyOwnFailedAndStop() {
        // given
        TradeHistory success = tradeHistoryRepository.save(make(USER_ID, SYMBOL, "entry", OrderResult.SUCCESS, ExchangeType.BYBIT));
        TradeHistory failed = tradeHistoryRepository.save(make(USER_ID, SYMBOL, "entry", OrderResult.FAILED, ExchangeType.BYBIT));
        TradeHistory stop = tradeHistoryRepository.save(make(USER_ID, SYMBOL, "stop", OrderResult.SUCCESS, ExchangeType.BYBIT));
        TradeHistory othersFailed = tradeHistoryRepository.save(make(2L, SYMBOL, "entry", OrderResult.FAILED, ExchangeType.BYBIT));
        testEntityManager.flush();

        // when - 성공 거래·타인 기록 ID까지 섞어서 요청
        tradeHistoryRepository.deleteFailedOrStoppedByIdInAndUserId(
                List.of(success.getId(), failed.getId(), stop.getId(), othersFailed.getId()), USER_ID);
        testEntityManager.clear();

        // then
        assertThat(tradeHistoryRepository.findAll()).extracting(TradeHistory::getId)
                .containsExactlyInAnyOrder(success.getId(), othersFailed.getId());
    }

    // ─────────────────────────────────────────────
    // 헬퍼 메서드
    // ─────────────────────────────────────────────

    private TradeHistory make(Long userId, String symbol, String orderType, OrderResult result, ExchangeType exchangeType) {
        TradeHistory h = makeTradeHistory(userId, symbol);
        h.setOrderType(orderType);
        h.setOrderResult(result);
        h.setExchangeType(exchangeType);
        return h;
    }

    private TradeHistory makeTradeHistory(Long userId, String symbol) {
        TradeHistory h = new TradeHistory();
        h.setExchangeType(ExchangeType.BYBIT);
        h.setUserId(userId);
        h.setSymbol(symbol);
        h.setSide(Side.LONG);
        h.setAmount(new BigDecimal("100"));
        h.setExecutedPrice(new BigDecimal("50000"));
        h.setOrderResult(OrderResult.SUCCESS);
        h.setPatternId(1L);
        h.setOrderType("ENTRY");
        return h;
    }
}
