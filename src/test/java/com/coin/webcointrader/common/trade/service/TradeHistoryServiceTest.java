package com.coin.webcointrader.common.trade.service;

import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.enums.OrderResult;
import com.coin.webcointrader.common.enums.Side;
import com.coin.webcointrader.common.trade.dto.DailyProfitLossDTO;
import com.coin.webcointrader.common.trade.dto.InvestmentHistoryDTO;
import com.coin.webcointrader.common.trade.dto.ProfitLossSummaryDTO;
import com.coin.webcointrader.common.trade.dto.SymbolProfitLossDTO;
import com.coin.webcointrader.common.trade.dto.TradeHistoryDTO;
import com.coin.webcointrader.common.trade.dto.WinLossCountDTO;
import com.coin.webcointrader.common.trade.entity.InvestmentHistory;
import com.coin.webcointrader.common.trade.entity.TradeHistory;
import com.coin.webcointrader.common.trade.repository.InvestmentHistoryRepository;
import com.coin.webcointrader.common.trade.repository.TradeHistoryRepository;
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
import static org.mockito.Mockito.never;

/** TradeHistoryService(MAIN 히스토리) 단위 테스트. 엔티티 ↔ DTO 변환과 Repository 위임을 검증한다. */
@ExtendWith(MockitoExtension.class)
class TradeHistoryServiceTest {

    @InjectMocks
    private TradeHistoryService service;

    @Mock
    private TradeHistoryRepository tradeHistoryRepository;

    @Mock
    private InvestmentHistoryRepository investmentHistoryRepository;

    private static final Long USER_ID = 1L;
    private static final ExchangeType EX = ExchangeType.BYBIT; // 조회·삭제 대상 거래소
    private static final LocalDateTime START = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 1, 31, 23, 59);

    private InvestmentHistory investmentEntity() {
        InvestmentHistory e = new InvestmentHistory();
        e.setExchangeType(ExchangeType.BYBIT);
        e.setUserId(USER_ID);
        e.setPatternId(10L);
        e.setSymbol("BTCUSDT");
        e.setSide(Side.LONG);
        e.setProfitLoss(new BigDecimal("12.5"));
        e.setEntryPrice(new BigDecimal("100"));
        e.setExitPrice(new BigDecimal("110"));
        e.setAmount(new BigDecimal("50"));
        e.setLeverage(10);
        e.setTpPrice(new BigDecimal("120"));
        e.setSlPrice(new BigDecimal("90"));
        e.setFundingFee(new BigDecimal("0.01"));
        ReflectionTestUtils.setField(e, "createdAt", START);
        return e;
    }

    @Test
    @DisplayName("saveTrade: DTO의 모든 필드를 TradeHistory 엔티티로 옮겨 저장한다")
    void saveTrade_mapsAllFields() {
        // given
        TradeHistoryDTO dto = TradeHistoryDTO.builder()
                .patternId(5L).userId(USER_ID).symbol("BTCUSDT").side(Side.SHORT)
                .amount(new BigDecimal("100")).executedPrice(new BigDecimal("65000"))
                .orderId("ORD-1").fee(new BigDecimal("0.5")).orderType("ENTRY")
                .orderResult(OrderResult.SUCCESS).errorMessage(null).exchangeType(ExchangeType.BYBIT).build();

        // when
        service.saveTrade(dto);

        // then
        ArgumentCaptor<TradeHistory> captor = ArgumentCaptor.forClass(TradeHistory.class);
        then(tradeHistoryRepository).should().save(captor.capture());
        TradeHistory saved = captor.getValue();
        assertThat(saved.getPatternId()).isEqualTo(5L);
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(saved.getSide()).isEqualTo(Side.SHORT);
        assertThat(saved.getAmount()).isEqualByComparingTo("100");
        assertThat(saved.getExecutedPrice()).isEqualByComparingTo("65000");
        assertThat(saved.getOrderId()).isEqualTo("ORD-1");
        assertThat(saved.getFee()).isEqualByComparingTo("0.5");
        assertThat(saved.getOrderType()).isEqualTo("ENTRY");
        assertThat(saved.getOrderResult()).isEqualTo(OrderResult.SUCCESS);
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
        ArgumentCaptor<TradeHistory> captor = ArgumentCaptor.forClass(TradeHistory.class);
        then(tradeHistoryRepository).should().save(captor.capture());
        assertThat(captor.getValue().getErrorMessage()).isEqualTo("가".repeat(500));
    }

    @Test
    @DisplayName("saveInvestment: 펀딩비를 포함한 모든 필드를 InvestmentHistory 엔티티로 옮겨 저장한다")
    void saveInvestment_mapsAllFieldsIncludingFundingFee() {
        // given
        InvestmentHistoryDTO dto = InvestmentHistoryDTO.builder()
                .userId(USER_ID).patternId(10L).symbol("ETHUSDT").side(Side.LONG)
                .profitLoss(new BigDecimal("3")).entryPrice(new BigDecimal("1")).exitPrice(new BigDecimal("2"))
                .amount(new BigDecimal("50")).leverage(5)
                .tpPrice(new BigDecimal("3")).slPrice(new BigDecimal("0.5"))
                .fundingFee(new BigDecimal("-0.02")).exchangeType(ExchangeType.BYBIT).build();

        // when
        service.saveInvestment(dto);

        // then
        ArgumentCaptor<InvestmentHistory> captor = ArgumentCaptor.forClass(InvestmentHistory.class);
        then(investmentHistoryRepository).should().save(captor.capture());
        InvestmentHistory saved = captor.getValue();
        assertThat(saved.getPatternId()).isEqualTo(10L);
        assertThat(saved.getSymbol()).isEqualTo("ETHUSDT");
        assertThat(saved.getLeverage()).isEqualTo(5);
        assertThat(saved.getFundingFee()).isEqualByComparingTo("-0.02");
        assertThat(saved.getTpPrice()).isEqualByComparingTo("3");
        assertThat(saved.getSlPrice()).isEqualByComparingTo("0.5");
        assertThat(saved.getExchangeType()).isEqualTo(ExchangeType.BYBIT);
    }

    @Test
    @DisplayName("findInvestments: 심볼이 없으면 날짜 범위 조회를 사용하고 DTO로 변환한다")
    void findInvestments_withoutSymbol() {
        // given
        Pageable pageable = PageRequest.of(0, 10);
        Page<InvestmentHistory> page = new PageImpl<>(List.of(investmentEntity()), pageable, 1);
        given(investmentHistoryRepository.findByUserIdAndExchangeTypeAndCreatedAtBetween(USER_ID, EX, START, END, pageable))
                .willReturn(page);

        // when
        Page<InvestmentHistoryDTO> result = service.findInvestments(USER_ID, EX, null, START, END, pageable);

        // then
        assertThat(result.getTotalElements()).isEqualTo(1);
        InvestmentHistoryDTO dto = result.getContent().get(0);
        assertThat(dto.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(dto.getFundingFee()).isEqualByComparingTo("0.01");
        assertThat(dto.getExchangeType()).isEqualTo(ExchangeType.BYBIT);
        assertThat(dto.getCreatedAt()).isEqualTo(START);
    }

    @Test
    @DisplayName("findInvestments: 심볼이 있으면 부분일치(대소문자 무시) 조회를 사용한다")
    void findInvestments_withSymbol() {
        // given
        Pageable pageable = PageRequest.of(0, 10);
        given(investmentHistoryRepository.findByUserIdAndExchangeTypeAndSymbolContainingIgnoreCaseAndCreatedAtBetween(
                USER_ID, EX, "btc", START, END, pageable))
                .willReturn(new PageImpl<>(List.of(investmentEntity()), pageable, 1));

        // when
        Page<InvestmentHistoryDTO> result = service.findInvestments(USER_ID, EX, "btc", START, END, pageable);

        // then
        assertThat(result.getContent()).hasSize(1);
        then(investmentHistoryRepository).should(never()).findByUserIdAndExchangeTypeAndCreatedAtBetween(
                USER_ID, EX, START, END, pageable);
    }

    @Test
    @DisplayName("findInvestments: 공백 심볼은 필터 없음으로 처리한다")
    void findInvestments_blankSymbolIsNoFilter() {
        // given
        Pageable pageable = PageRequest.of(0, 10);
        given(investmentHistoryRepository.findByUserIdAndExchangeTypeAndCreatedAtBetween(USER_ID, EX, START, END, pageable))
                .willReturn(new PageImpl<>(List.of(), pageable, 0));

        // when
        service.findInvestments(USER_ID, EX, "  ", START, END, pageable);

        // then
        then(investmentHistoryRepository).should().findByUserIdAndExchangeTypeAndCreatedAtBetween(USER_ID, EX, START, END, pageable);
    }

    @Test
    @DisplayName("sumProfitLoss: 심볼 없을 때 수익/손실 합계 행을 DTO로 변환한다")
    void sumProfitLoss_withoutSymbol() {
        // given
        given(investmentHistoryRepository.sumProfitLossByUserIdAndCreatedAtBetween(USER_ID, EX, START, END))
                .willReturn(List.<Object[]>of(new Object[]{new BigDecimal("30"), new BigDecimal("-10"), new BigDecimal("200")}));

        // when
        ProfitLossSummaryDTO result = service.sumProfitLoss(USER_ID, EX, null, START, END);

        // then
        assertThat(result.getTotalProfit()).isEqualByComparingTo("30");
        assertThat(result.getTotalAmount()).isEqualByComparingTo("200");
        assertThat(result.getTotalLoss()).isEqualByComparingTo("-10");
    }

    @Test
    @DisplayName("sumProfitLoss: 심볼이 있으면 심볼 필터 쿼리를 사용한다")
    void sumProfitLoss_withSymbol() {
        // given
        given(investmentHistoryRepository.sumProfitLossByUserIdAndCreatedAtBetweenAndSymbol(USER_ID, EX, START, END, "BTC"))
                .willReturn(List.<Object[]>of(new Object[]{new BigDecimal("5"), new BigDecimal("-1"), new BigDecimal("50")}));

        // when
        ProfitLossSummaryDTO result = service.sumProfitLoss(USER_ID, EX, "BTC", START, END);

        // then
        assertThat(result.getTotalProfit()).isEqualByComparingTo("5");
        assertThat(result.getTotalLoss()).isEqualByComparingTo("-1");
    }

    @Test
    @DisplayName("countWinLoss: 결과 행이 없으면 0/0/0 으로 반환한다")
    void countWinLoss_emptyReturnsZeros() {
        // given
        given(investmentHistoryRepository.countWinLoss(USER_ID, EX, START, END)).willReturn(List.of());

        // when
        WinLossCountDTO result = service.countWinLoss(USER_ID, EX, START, END);

        // then
        assertThat(result.getWinCount()).isZero();
        assertThat(result.getLossCount()).isZero();
        assertThat(result.getTotalCount()).isZero();
    }

    @Test
    @DisplayName("countWinLoss: SUM 결과가 null 이면 0 으로 변환한다")
    void countWinLoss_nullSumsBecomeZero() {
        // given
        given(investmentHistoryRepository.countWinLoss(USER_ID, EX, START, END))
                .willReturn(List.<Object[]>of(new Object[]{null, null, 0L}));

        // when
        WinLossCountDTO result = service.countWinLoss(USER_ID, EX, START, END);

        // then
        assertThat(result.getWinCount()).isZero();
        assertThat(result.getLossCount()).isZero();
        assertThat(result.getTotalCount()).isZero();
    }

    @Test
    @DisplayName("countWinLossBySymbol: 승/패/전체를 long 으로 변환한다")
    void countWinLossBySymbol_mapsCounts() {
        // given
        given(investmentHistoryRepository.countWinLossBySymbol(USER_ID, EX, START, END, "BTC"))
                .willReturn(List.<Object[]>of(new Object[]{3L, 2L, 5L}));

        // when
        WinLossCountDTO result = service.countWinLossBySymbol(USER_ID, EX, "BTC", START, END);

        // then
        assertThat(result.getWinCount()).isEqualTo(3);
        assertThat(result.getLossCount()).isEqualTo(2);
        assertThat(result.getTotalCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("findDailyProfitLoss: 심볼 유무에 따라 쿼리를 분기하고 DTO로 변환한다")
    void findDailyProfitLoss_branchesOnSymbol() {
        // given
        given(investmentHistoryRepository.findDailyProfitLoss(USER_ID, EX, START, END))
                .willReturn(List.<Object[]>of(new Object[]{"2026-01-02", new BigDecimal("7")}));
        given(investmentHistoryRepository.findDailyProfitLossBySymbol(USER_ID, EX, START, END, "ETH"))
                .willReturn(List.<Object[]>of(new Object[]{"2026-01-03", new BigDecimal("-2")}));

        // when
        List<DailyProfitLossDTO> all = service.findDailyProfitLoss(USER_ID, EX, null, START, END);
        List<DailyProfitLossDTO> eth = service.findDailyProfitLoss(USER_ID, EX, "ETH", START, END);

        // then
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getDate()).isEqualTo("2026-01-02");
        assertThat(all.get(0).getProfitLoss()).isEqualByComparingTo("7");
        assertThat(eth.get(0).getDate()).isEqualTo("2026-01-03");
        assertThat(eth.get(0).getProfitLoss()).isEqualByComparingTo("-2");
    }

    @Test
    @DisplayName("findProfitLossBySymbol: 심볼 유무에 따라 쿼리를 분기하고 DTO로 변환한다")
    void findProfitLossBySymbol_branchesOnSymbol() {
        // given
        given(investmentHistoryRepository.findProfitLossBySymbol(USER_ID, EX, START, END))
                .willReturn(List.<Object[]>of(new Object[]{"BTCUSDT", new BigDecimal("9")}));
        given(investmentHistoryRepository.findProfitLossBySymbolFiltered(USER_ID, EX, START, END, "ETH"))
                .willReturn(List.<Object[]>of(new Object[]{"ETHUSDT", new BigDecimal("1")}));

        // when
        List<SymbolProfitLossDTO> all = service.findProfitLossBySymbol(USER_ID, EX, "", START, END);
        List<SymbolProfitLossDTO> eth = service.findProfitLossBySymbol(USER_ID, EX, "ETH", START, END);

        // then
        assertThat(all.get(0).getSymbol()).isEqualTo("BTCUSDT");
        assertThat(eth.get(0).getSymbol()).isEqualTo("ETHUSDT");
        assertThat(eth.get(0).getProfitLoss()).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("findInvestment: 본인 데이터가 있으면 DTO, 없으면 empty 를 반환한다")
    void findInvestment_foundAndEmpty() {
        // given
        given(investmentHistoryRepository.findByIdAndUserId(1L, USER_ID)).willReturn(Optional.of(investmentEntity()));
        given(investmentHistoryRepository.findByIdAndUserId(2L, USER_ID)).willReturn(Optional.empty());

        // when & then
        assertThat(service.findInvestment(1L, USER_ID)).isPresent();
        assertThat(service.findInvestment(1L, USER_ID).get().getPatternId()).isEqualTo(10L);
        assertThat(service.findInvestment(2L, USER_ID)).isEmpty();
    }

    @Test
    @DisplayName("findTradesUpTo: 단계 거래를 최신순 DTO 목록으로 변환한다")
    void findTradesUpTo_mapsToDto() {
        // given
        TradeHistory t = new TradeHistory();
        t.setExchangeType(ExchangeType.BYBIT);
        t.setPatternId(10L);
        t.setUserId(USER_ID);
        t.setSymbol("BTCUSDT");
        t.setSide(Side.LONG);
        t.setAmount(new BigDecimal("50"));
        t.setExecutedPrice(new BigDecimal("100"));
        t.setOrderType("SELL");
        t.setOrderResult(OrderResult.SUCCESS);
        ReflectionTestUtils.setField(t, "createdAt", END);
        given(tradeHistoryRepository.findByPatternIdAndUserIdAndCreatedAtLessThanEqualOrderByCreatedAtDesc(
                10L, USER_ID, END)).willReturn(List.of(t));

        // when
        List<TradeHistoryDTO> result = service.findTradesUpTo(10L, USER_ID, END);

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getOrderType()).isEqualTo("SELL");
        assertThat(result.get(0).getCreatedAt()).isEqualTo(END);
    }

    @Test
    @DisplayName("findFailedOrStoppedTrades: 빈 심볼은 null(전체)로 넘기고, 실패·중지 기록을 사유와 함께 DTO로 변환한다")
    void findFailedOrStoppedTrades_mapsWithReason() {
        // given
        TradeHistory e = new TradeHistory();
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
        given(tradeHistoryRepository.findFailedOrStopped(USER_ID, EX, null, START, END, pageable))
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
        given(tradeHistoryRepository.findFailedOrStopped(USER_ID, EX, "btc", START, END, pageable))
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
        service.deleteTrades(List.of(1L, 2L), USER_ID);
        service.deleteAllTrades(USER_ID, EX);
        service.deleteInvestments(List.of(3L), USER_ID);
        service.deleteAllInvestments(USER_ID, EX);

        // then
        then(tradeHistoryRepository).should().deleteFailedOrStoppedByIdInAndUserId(List.of(1L, 2L), USER_ID);
        then(tradeHistoryRepository).should().deleteAllByUserIdAndExchangeType(USER_ID, EX);
        then(investmentHistoryRepository).should().deleteAllByIdInAndUserId(List.of(3L), USER_ID);
        then(investmentHistoryRepository).should().deleteAllByUserIdAndExchangeType(USER_ID, EX);
    }
}
