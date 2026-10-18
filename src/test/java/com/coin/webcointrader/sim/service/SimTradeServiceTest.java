package com.coin.webcointrader.sim.service;

import com.coin.webcointrader.bybit.dto.request.CreateOrderRequest;
import com.coin.webcointrader.sim.entity.SimWallet;
import com.coin.webcointrader.trade.dto.TradeHistoryDTO;
import com.coin.webcointrader.common.enums.TradeOrderType;
import com.coin.webcointrader.sim.repository.SimWalletRepository;
import com.coin.webcointrader.common.enums.OrderResult;
import com.coin.webcointrader.common.exception.CustomException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class SimTradeServiceTest {

    @InjectMocks
    private SimTradeService simTradeService;

    @Mock
    private SimHistoryService simHistoryService;

    @Mock
    private SimWalletRepository simWalletRepository;

    @Test
    @DisplayName("ENTRY 주문 시 주문 금액만큼 SimWallet 잔고를 차감한다")
    void placeOrder_entry_deductsBalance() {
        // given
        Long userId = 1L;
        BigDecimal initialBalance = new BigDecimal("1000.00");
        BigDecimal orderAmount = new BigDecimal("100.00");

        SimWallet wallet = new SimWallet();
        wallet.setBalance(initialBalance);

        TradeHistoryDTO history = new TradeHistoryDTO();
        history.setOrderType(TradeOrderType.ENTRY.getTradeOrderType());
        history.setAmount(orderAmount);

        CreateOrderRequest request = CreateOrderRequest.builder()
                .symbol("BTCUSDT")
                .side("Buy")
                .orderType("Market")
                .qty("0.002")
                .build();

        given(simWalletRepository.findByUserId(userId)).willReturn(Optional.of(wallet));

        // when
        simTradeService.placeOrder(request, userId, history);

        // then - 잔고 = 1000 - 100 = 900
        assertThat(wallet.getBalance()).isEqualByComparingTo(new BigDecimal("900.00"));
        then(simWalletRepository).should().save(wallet);
    }

    @Test
    @DisplayName("SELL 주문 시 SimWallet 잔고를 차감하지 않는다")
    void placeOrder_sell_doesNotDeductBalance() {
        // given
        Long userId = 1L;
        BigDecimal initialBalance = new BigDecimal("1000.00");
        BigDecimal orderAmount = new BigDecimal("100.00");

        SimWallet wallet = new SimWallet();
        wallet.setBalance(initialBalance);

        TradeHistoryDTO history = new TradeHistoryDTO();
        history.setOrderType(TradeOrderType.SELL.getTradeOrderType());
        history.setAmount(orderAmount);

        CreateOrderRequest request = CreateOrderRequest.builder()
                .symbol("BTCUSDT")
                .side("Sell")
                .orderType("Market")
                .qty("0.002")
                .build();

        given(simWalletRepository.findByUserId(userId)).willReturn(Optional.of(wallet));

        // when
        simTradeService.placeOrder(request, userId, history);

        // then - 잔고 변동 없음 (매도 수익은 applyProfitLoss에서 반영)
        assertThat(wallet.getBalance()).isEqualByComparingTo(new BigDecimal("1000.00"));
        then(simWalletRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("LIQUIDATION 주문 시 SimWallet 잔고를 차감하지 않는다")
    void placeOrder_liquidation_doesNotDeductBalance() {
        // given
        Long userId = 1L;
        BigDecimal initialBalance = new BigDecimal("1000.00");
        BigDecimal orderAmount = new BigDecimal("100.00");

        SimWallet wallet = new SimWallet();
        wallet.setBalance(initialBalance);

        TradeHistoryDTO history = new TradeHistoryDTO();
        history.setOrderType(TradeOrderType.LIQUIDATION.getTradeOrderType());
        history.setAmount(orderAmount);

        CreateOrderRequest request = CreateOrderRequest.builder()
                .symbol("BTCUSDT")
                .side("Sell")
                .orderType("Market")
                .qty("0.002")
                .build();

        given(simWalletRepository.findByUserId(userId)).willReturn(Optional.of(wallet));

        // when
        simTradeService.placeOrder(request, userId, history);

        // then - 잔고 변동 없음 (손익은 applyProfitLoss에서 반영)
        assertThat(wallet.getBalance()).isEqualByComparingTo(new BigDecimal("1000.00"));
        then(simWalletRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("ENTRY 주문 시 잔고가 부족하면 실패 이력을 SimHistoryService로 저장하고 예외를 던진다")
    void placeOrder_entry_insufficientBalance_savesFailedHistory() {
        // given: 잔고 10 < 주문 금액 100
        SimWallet wallet = new SimWallet();
        wallet.setBalance(new BigDecimal("10"));
        TradeHistoryDTO history = new TradeHistoryDTO();
        history.setOrderType(TradeOrderType.ENTRY.getTradeOrderType());
        history.setAmount(new BigDecimal("100"));
        CreateOrderRequest request = CreateOrderRequest.builder().symbol("BTCUSDT").side("Buy")
                .orderType("Market").qty("0.002").build();
        given(simWalletRepository.findByUserId(1L)).willReturn(Optional.of(wallet));

        // when & then
        assertThatThrownBy(() -> simTradeService.placeOrder(request, 1L, history))
                .isInstanceOf(CustomException.class);
        then(simHistoryService).should().saveTrade(history);
        assertThat(history.getOrderResult()).isEqualTo(OrderResult.FAILED);
        assertThat(history.getErrorMessage()).isEqualTo("모의투자 잔고 부족");
        assertThat(history.getExecutedPrice()).isEqualByComparingTo("0");
    }
}
