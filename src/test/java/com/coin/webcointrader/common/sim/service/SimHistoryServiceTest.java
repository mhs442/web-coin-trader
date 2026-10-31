package com.coin.webcointrader.common.sim.service;

import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.enums.OrderResult;
import com.coin.webcointrader.common.enums.Side;
import com.coin.webcointrader.common.sim.entity.SimInvestmentHistory;
import com.coin.webcointrader.common.sim.entity.SimTradeHistory;
import com.coin.webcointrader.common.sim.repository.SimInvestmentHistoryRepository;
import com.coin.webcointrader.common.sim.repository.SimTradeHistoryRepository;
import com.coin.webcointrader.common.trade.dto.InvestmentHistoryDTO;
import com.coin.webcointrader.common.trade.dto.ProfitLossSummaryDTO;
import com.coin.webcointrader.common.trade.dto.TradeHistoryDTO;
import com.coin.webcointrader.common.trade.dto.WinLossCountDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

/** SimHistoryService(SIM 히스토리) 단위 테스트. 엔티티 ↔ DTO 변환과 Repository 위임을 검증한다. */
@ExtendWith(MockitoExtension.class)
class SimHistoryServiceTest {

    @InjectMocks
    private SimHistoryService service;

    @Mock
    private SimTradeHistoryRepository simTradeHistoryRepository;

    @Mock
    private SimInvestmentHistoryRepository simInvestmentHistoryRepository;

    private static final Long USER_ID = 1L;
    private static final ExchangeType EX = ExchangeType.BYBIT; // 조회·삭제 대상 거래소
    private static final LocalDateTime START = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 1, 31, 23, 59);

    @Test
    @DisplayName("saveTrade: DTO를 SimTradeHistory 엔티티로 옮겨 저장한다")
    void saveTrade_mapsAllFields() {
        // given
        TradeHistoryDTO dto = TradeHistoryDTO.builder()
                .patternId(5L).userId(USER_ID).symbol("BTCUSDT").side(Side.LONG)
                .amount(new BigDecimal("100")).executedPrice(BigDecimal.ZERO)
                .orderId("SIM-1").fee(new BigDecimal("0.1")).orderType("ENTRY")
                .orderResult(OrderResult.FAILED).errorMessage("모의투자 잔고 부족").exchangeType(ExchangeType.BYBIT).build();

        // when
        service.saveTrade(dto);

        // then
        ArgumentCaptor<SimTradeHistory> captor = ArgumentCaptor.forClass(SimTradeHistory.class);
        then(simTradeHistoryRepository).should().save(captor.capture());
        SimTradeHistory saved = captor.getValue();
        assertThat(saved.getPatternId()).isEqualTo(5L);
        assertThat(saved.getOrderId()).isEqualTo("SIM-1");
        assertThat(saved.getOrderResult()).isEqualTo(OrderResult.FAILED);
        assertThat(saved.getErrorMessage()).isEqualTo("모의투자 잔고 부족");
        assertThat(saved.getFee()).isEqualByComparingTo("0.1");
        assertThat(saved.getExchangeType()).isEqualTo(ExchangeType.BYBIT);
    }

    @Test
    @DisplayName("saveTrade: 실패 사유가 500자를 넘으면 500자로 잘라 저장한다 (error_message VARCHAR(500))")
    void saveTrade_truncatesLongErrorMessage() {
        // given
        TradeHistoryDTO dto = TradeHistoryDTO.builder()
                .patternId(5L).userId(USER_ID).symbol("BTCUSDT").side(Side.LONG)
                .amount(BigDecimal.ZERO).executedPrice(BigDecimal.ZERO).orderType("ENTRY")
                .orderResult(OrderResult.FAILED).errorMessage("가".repeat(600)).exchangeType(ExchangeType.BYBIT).build();

        // when
        service.saveTrade(dto);

        // then
        ArgumentCaptor<SimTradeHistory> captor = ArgumentCaptor.forClass(SimTradeHistory.class);
        then(simTradeHistoryRepository).should().save(captor.capture());
        assertThat(captor.getValue().getErrorMessage()).isEqualTo("가".repeat(500));
    }

    @Test
    @DisplayName("saveInvestment: DTO를 SimInvestmentHistory 엔티티로 옮겨 저장한다 (펀딩비는 SIM에 없음)")
    void saveInvestment_mapsAllFields() {
        // given
        InvestmentHistoryDTO dto = InvestmentHistoryDTO.builder()
                .userId(USER_ID).patternId(10L).symbol("ETHUSDT").side(Side.SHORT)
                .profitLoss(new BigDecimal("-3")).entryPrice(new BigDecimal("2")).exitPrice(new BigDecimal("3"))
                .amount(new BigDecimal("50")).leverage(3)
                .tpPrice(new BigDecimal("1")).slPrice(new BigDecimal("4")).exchangeType(ExchangeType.BYBIT).build();

        // when
        service.saveInvestment(dto);

        // then
        ArgumentCaptor<SimInvestmentHistory> captor = ArgumentCaptor.forClass(SimInvestmentHistory.class);
        then(simInvestmentHistoryRepository).should().save(captor.capture());
        SimInvestmentHistory saved = captor.getValue();
        assertThat(saved.getPatternId()).isEqualTo(10L);
        assertThat(saved.getSide()).isEqualTo(Side.SHORT);
        assertThat(saved.getLeverage()).isEqualTo(3);
        assertThat(saved.getProfitLoss()).isEqualByComparingTo("-3");
        assertThat(saved.getExchangeType()).isEqualTo(ExchangeType.BYBIT);
    }

    @Test
    @DisplayName("findInvestments: 심볼 유무에 따라 쿼리를 분기하고 DTO로 변환한다 (fundingFee 는 null)")
    void findInvestments_branchesAndMaps() {
        // given
        Pageable pageable = PageRequest.of(0, 10);
        SimInvestmentHistory e = new SimInvestmentHistory();
        e.setExchangeType(ExchangeType.BYBIT);
        e.setUserId(USER_ID);
        e.setPatternId(10L);
        e.setSymbol("BTCUSDT");
        e.setSide(Side.LONG);
        e.setProfitLoss(BigDecimal.ONE);
        e.setEntryPrice(BigDecimal.ONE);
        e.setExitPrice(BigDecimal.TEN);
        e.setAmount(BigDecimal.TEN);
        e.setLeverage(2);
        ReflectionTestUtils.setField(e, "createdAt", START);
        given(simInvestmentHistoryRepository.findByUserIdAndExchangeTypeAndCreatedAtBetween(USER_ID, EX, START, END, pageable))
                .willReturn(new PageImpl<>(List.of(e), pageable, 1));
        given(simInvestmentHistoryRepository.findByUserIdAndExchangeTypeAndSymbolContainingIgnoreCaseAndCreatedAtBetween(
                USER_ID, EX, "btc", START, END, pageable))
                .willReturn(new PageImpl<>(List.of(e), pageable, 1));

        // when
        Page<InvestmentHistoryDTO> noSymbol = service.findInvestments(USER_ID, EX, null, START, END, pageable);
        Page<InvestmentHistoryDTO> withSymbol = service.findInvestments(USER_ID, EX, "btc", START, END, pageable);

        // then
        assertThat(noSymbol.getContent().get(0).getFundingFee()).isNull();
        assertThat(noSymbol.getContent().get(0).getCreatedAt()).isEqualTo(START);
        assertThat(withSymbol.getContent()).hasSize(1);
    }

    @Test
    @DisplayName("sumProfitLoss / countWinLoss: 집계 결과 행을 DTO로 변환한다")
    void aggregates_mapToDto() {
        // given
        given(simInvestmentHistoryRepository.sumProfitLossByUserIdAndCreatedAtBetweenAndSymbol(USER_ID, EX, START, END, "BTC"))
                .willReturn(List.<Object[]>of(new Object[]{new BigDecimal("4"), new BigDecimal("-2"), new BigDecimal("40")}));
        given(simInvestmentHistoryRepository.countWinLoss(USER_ID, EX, START, END))
                .willReturn(List.<Object[]>of(new Object[]{1L, 2L, 3L}));

        // when
        ProfitLossSummaryDTO sum = service.sumProfitLoss(USER_ID, EX, "BTC", START, END);
        WinLossCountDTO wl = service.countWinLoss(USER_ID, EX, START, END);

        // then
        assertThat(sum.getTotalProfit()).isEqualByComparingTo("4");
        assertThat(sum.getTotalLoss()).isEqualByComparingTo("-2");
        assertThat(sum.getTotalAmount()).isEqualByComparingTo("40");
        assertThat(wl.getWinCount()).isEqualTo(1);
        assertThat(wl.getLossCount()).isEqualTo(2);
        assertThat(wl.getTotalCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("findInvestment / findTradesUpTo: 엔티티를 DTO로 변환한다")
    void finders_mapToDto() {
        // given
        SimInvestmentHistory inv = new SimInvestmentHistory();
        inv.setExchangeType(ExchangeType.BYBIT);
        inv.setPatternId(10L);
        inv.setSide(Side.LONG);
        given(simInvestmentHistoryRepository.findByIdAndUserId(1L, USER_ID)).willReturn(Optional.of(inv));
        given(simInvestmentHistoryRepository.findByIdAndUserId(2L, USER_ID)).willReturn(Optional.empty());

        SimTradeHistory trade = new SimTradeHistory();

        trade.setExchangeType(ExchangeType.BYBIT);
        trade.setOrderType("ENTRY");
        trade.setSide(Side.LONG);
        given(simTradeHistoryRepository.findByPatternIdAndUserIdAndCreatedAtLessThanEqualOrderByCreatedAtDesc(
                10L, USER_ID, END)).willReturn(List.of(trade));

        // when & then
        assertThat(service.findInvestment(1L, USER_ID).get().getPatternId()).isEqualTo(10L);
        assertThat(service.findInvestment(2L, USER_ID)).isEmpty();
        assertThat(service.findTradesUpTo(10L, USER_ID, END).get(0).getOrderType()).isEqualTo("ENTRY");
        // 거래소 타입도 DTO로 옮겨진다
        assertThat(service.findInvestment(1L, USER_ID).get().getExchangeType()).isEqualTo(ExchangeType.BYBIT);
        assertThat(service.findTradesUpTo(10L, USER_ID, END).get(0).getExchangeType()).isEqualTo(ExchangeType.BYBIT);
    }

    @Test
    @DisplayName("findFailedOrStoppedTrades: 빈 심볼은 null(전체)로 넘기고, 실패·중지 기록을 사유와 함께 DTO로 변환한다")
    void findFailedOrStoppedTrades_mapsWithReason() {
        // given
        SimTradeHistory e = new SimTradeHistory();
        e.setExchangeType(EX);
        e.setUserId(USER_ID);
        e.setSymbol("BTCUSDT");
        e.setSide(Side.LONG);
        e.setAmount(new BigDecimal("100"));
        e.setExecutedPrice(BigDecimal.ZERO);
        e.setOrderType("entry");
        e.setOrderResult(OrderResult.FAILED);
        e.setErrorMessage("잔고 부족");
        ReflectionTestUtils.setField(e, "createdAt", START);
        Pageable pageable = PageRequest.of(0, 20);
        given(simTradeHistoryRepository.findFailedOrStopped(USER_ID, EX, null, START, END, pageable))
                .willReturn(new PageImpl<>(List.of(e), pageable, 1));

        // when - 공백 심볼
        Page<TradeHistoryDTO> result = service.findFailedOrStoppedTrades(USER_ID, EX, "  ", START, END, pageable);

        // then
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getOrderResult()).isEqualTo(OrderResult.FAILED);
        assertThat(result.getContent().get(0).getErrorMessage()).isEqualTo("잔고 부족");
    }

    @Test
    @DisplayName("findFailedOrStoppedTrades: 심볼 키워드가 있으면 그대로 Repository에 넘긴다")
    void findFailedOrStoppedTrades_passesSymbol() {
        // given
        Pageable pageable = PageRequest.of(0, 20);
        given(simTradeHistoryRepository.findFailedOrStopped(USER_ID, EX, "btc", START, END, pageable))
                .willReturn(Page.empty(pageable));

        // when
        Page<TradeHistoryDTO> result = service.findFailedOrStoppedTrades(USER_ID, EX, "btc", START, END, pageable);

        // then
        assertThat(result.getContent()).isEmpty();
    }

    @Test
    @DisplayName("삭제 메서드는 각 Repository 삭제로 위임한다")
    void delete_delegatesToRepositories() {
        // when
        service.deleteTrades(List.of(1L), USER_ID);
        service.deleteAllTrades(USER_ID, EX);
        service.deleteInvestments(List.of(2L), USER_ID);
        service.deleteAllInvestments(USER_ID, EX);

        // then
        then(simTradeHistoryRepository).should().deleteFailedOrStoppedByIdInAndUserId(List.of(1L), USER_ID);
        then(simTradeHistoryRepository).should().deleteAllByUserIdAndExchangeType(USER_ID, EX);
        then(simInvestmentHistoryRepository).should().deleteAllByIdInAndUserId(List.of(2L), USER_ID);
        then(simInvestmentHistoryRepository).should().deleteAllByUserIdAndExchangeType(USER_ID, EX);
    }
}
