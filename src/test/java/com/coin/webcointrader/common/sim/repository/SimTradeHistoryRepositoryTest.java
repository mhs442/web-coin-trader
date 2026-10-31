package com.coin.webcointrader.common.sim.repository;

import com.coin.webcointrader.common.config.JpaConfig;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.enums.OrderResult;
import com.coin.webcointrader.common.enums.Side;
import com.coin.webcointrader.common.sim.entity.SimTradeHistory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * 모의 거래 히스토리 리포지토리 통합 테스트.
 * 마이페이지 실패·중지 기록 조회·선택 삭제가 실패(FAILED) 거래와 중지(stop) 기록만 대상으로 하는지 검증한다.
 */
@DataJpaTest
@Import(JpaConfig.class)
class SimTradeHistoryRepositoryTest {

    @Autowired
    private SimTradeHistoryRepository repository;

    @Autowired
    private TestEntityManager testEntityManager;

    private static final Long USER_ID = 1L;
    private static final String SYMBOL = "BTCUSDT";

    @Test
    @DisplayName("findFailedOrStopped: 사용자·거래소의 실패 거래와 중지 기록만 반환한다")
    void findFailedOrStopped_returnsOnlyFailedAndStop() {
        // given - 성공 진입(제외), 실패 진입, 중지 기록, 다른 사용자 실패(제외), 다른 거래소 실패(제외)
        repository.save(make(USER_ID, SYMBOL, "entry", OrderResult.SUCCESS, ExchangeType.BYBIT));
        SimTradeHistory failed = repository.save(make(USER_ID, SYMBOL, "entry", OrderResult.FAILED, ExchangeType.BYBIT));
        SimTradeHistory stop = repository.save(make(USER_ID, SYMBOL, "stop", OrderResult.SUCCESS, ExchangeType.BYBIT));
        repository.save(make(2L, SYMBOL, "entry", OrderResult.FAILED, ExchangeType.BYBIT));
        repository.save(make(USER_ID, SYMBOL, "entry", OrderResult.FAILED, ExchangeType.BINANCE));

        // when
        Page<SimTradeHistory> result = repository.findFailedOrStopped(
                USER_ID, ExchangeType.BYBIT, null, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1),
                PageRequest.of(0, 20));

        // then
        assertThat(result.getContent()).extracting(SimTradeHistory::getId)
                .containsExactlyInAnyOrder(failed.getId(), stop.getId());
    }

    @Test
    @DisplayName("findFailedOrStopped: 심볼 키워드는 대소문자 구분 없이 부분일치로 거른다")
    void findFailedOrStopped_filtersBySymbolKeyword() {
        // given
        SimTradeHistory btc = repository.save(make(USER_ID, "BTCUSDT", "entry", OrderResult.FAILED, ExchangeType.BYBIT));
        repository.save(make(USER_ID, "ETHUSDT", "entry", OrderResult.FAILED, ExchangeType.BYBIT));

        // when
        Page<SimTradeHistory> result = repository.findFailedOrStopped(
                USER_ID, ExchangeType.BYBIT, "btc", LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1),
                PageRequest.of(0, 20));

        // then
        assertThat(result.getContent()).extracting(SimTradeHistory::getId).containsExactly(btc.getId());
    }

    @Test
    @DisplayName("deleteFailedOrStoppedByIdInAndUserId: 본인의 실패·중지 기록만 지우고 성공 거래·타인 기록은 남긴다")
    void deleteFailedOrStopped_onlyOwnFailedAndStop() {
        // given
        SimTradeHistory success = repository.save(make(USER_ID, SYMBOL, "entry", OrderResult.SUCCESS, ExchangeType.BYBIT));
        SimTradeHistory failed = repository.save(make(USER_ID, SYMBOL, "entry", OrderResult.FAILED, ExchangeType.BYBIT));
        SimTradeHistory stop = repository.save(make(USER_ID, SYMBOL, "stop", OrderResult.SUCCESS, ExchangeType.BYBIT));
        SimTradeHistory othersFailed = repository.save(make(2L, SYMBOL, "entry", OrderResult.FAILED, ExchangeType.BYBIT));
        testEntityManager.flush();

        // when - 성공 거래·타인 기록 ID까지 섞어서 요청
        repository.deleteFailedOrStoppedByIdInAndUserId(
                List.of(success.getId(), failed.getId(), stop.getId(), othersFailed.getId()), USER_ID);
        testEntityManager.clear();

        // then
        assertThat(repository.findAll()).extracting(SimTradeHistory::getId)
                .containsExactlyInAnyOrder(success.getId(), othersFailed.getId());
    }

    // ─────────────────────────────────────────────
    // 헬퍼 메서드
    // ─────────────────────────────────────────────

    private SimTradeHistory make(Long userId, String symbol, String orderType, OrderResult result, ExchangeType exchangeType) {
        SimTradeHistory h = new SimTradeHistory();
        h.setExchangeType(exchangeType);
        h.setUserId(userId);
        h.setSymbol(symbol);
        h.setSide(Side.LONG);
        h.setAmount(new BigDecimal("100"));
        h.setExecutedPrice(new BigDecimal("50000"));
        h.setOrderResult(result);
        h.setPatternId(1L);
        h.setOrderType(orderType);
        return h;
    }
}
