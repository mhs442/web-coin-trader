package com.coin.webcointrader.sim.service;

import com.coin.webcointrader.common.dto.request.CreateOrderRequest;
import com.coin.webcointrader.common.entity.SimWallet;
import com.coin.webcointrader.common.entity.TradeHistory;
import com.coin.webcointrader.common.enums.TradeOrderType;
import com.coin.webcointrader.sim.repository.SimTradeHistoryRepository;
import com.coin.webcointrader.sim.repository.SimWalletRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class SimTradeServiceTest {

    @InjectMocks
    private SimTradeService simTradeService;

    @Mock
    private SimTradeHistoryRepository simTradeHistoryRepository;

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

        TradeHistory history = new TradeHistory();
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

        TradeHistory history = new TradeHistory();
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

        TradeHistory history = new TradeHistory();
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
}
