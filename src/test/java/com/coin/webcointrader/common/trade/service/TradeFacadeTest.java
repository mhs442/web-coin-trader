package com.coin.webcointrader.common.trade.service;

import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.enums.OrderCancelResult;
import com.coin.webcointrader.common.enums.OrderResult;
import com.coin.webcointrader.common.enums.OrderSide;
import com.coin.webcointrader.common.enums.TradeMode;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.ExchangeClientRegistry;
import com.coin.webcointrader.common.trade.client.ExchangeTradeClient;
import com.coin.webcointrader.common.trade.dto.PositionCloseDTO;
import com.coin.webcointrader.common.trade.dto.ClosedPnlDTO;
import com.coin.webcointrader.common.trade.dto.ClosedPnlQueryDTO;
import com.coin.webcointrader.common.trade.dto.ExecutionDTO;
import com.coin.webcointrader.common.trade.dto.OrderRequestDTO;
import com.coin.webcointrader.common.trade.dto.OrderResultDTO;
import com.coin.webcointrader.common.account.dto.WalletBalanceDTO;
import com.coin.webcointrader.common.sim.service.SimHistoryService;
import com.coin.webcointrader.common.sim.service.SimTradeService;
import com.coin.webcointrader.common.sim.service.SimWalletService;
import com.coin.webcointrader.common.trade.dto.TradeHistoryDTO;
import com.coin.webcointrader.common.wallet.service.WalletService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

/**
 * 거래 Facade 단위 테스트.
 * MAIN은 거래소 타입별 거래 클라이언트로, SIM은 모의투자 서비스로 위임하는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class TradeFacadeTest {

    @InjectMocks
    private TradeFacade tradeFacade;

    @Mock
    private ExchangeClientRegistry registry;

    @Mock
    private ExchangeTradeClient bybitTrade;

    @Mock
    private SimTradeService simTradeService;

    @Mock
    private WalletService walletService;

    @Mock
    private SimWalletService simWalletService;

    @Mock
    private TradeHistoryService tradeHistoryService;

    @Mock
    private SimHistoryService simHistoryService;

    private static final OrderRequestDTO ORDER = OrderRequestDTO.builder()
            .symbol("BTCUSDT").side(OrderSide.BUY).qty("0.01").clientOrderId("q1-E-1").build();

    // ─── placeOrder ───

    @Test
    @DisplayName("placeOrder: MAIN은 거래소 타입의 거래 클라이언트로 주문한다")
    void placeOrder_main_routesToExchange() {
        OrderResultDTO expected = OrderResultDTO.builder().orderId("o1").build();
        given(registry.trade(ExchangeType.BYBIT)).willReturn(bybitTrade);
        given(bybitTrade.placeOrder(ORDER, 1L)).willReturn(expected);

        OrderResultDTO result = tradeFacade.placeOrder(ORDER, 1L, null, ExchangeType.BYBIT, TradeMode.MAIN);

        assertThat(result).isSameAs(expected);
        then(simTradeService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("placeOrder: SIM은 모의투자 서비스로 주문하고 거래소는 호출하지 않는다")
    void placeOrder_sim_routesToSim() {
        TradeHistoryDTO history = new TradeHistoryDTO();
        OrderResultDTO expected = OrderResultDTO.builder().orderId("SIM-1").build();
        given(simTradeService.placeOrder(ORDER, 1L, history)).willReturn(expected);

        OrderResultDTO result = tradeFacade.placeOrder(ORDER, 1L, history, ExchangeType.BYBIT, TradeMode.SIM);

        assertThat(result).isSameAs(expected);
        then(registry).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("placeOrder: MAIN 주문이 거래소에서 거부(ORDER_FAILED)되면 실패 이력을 저장하고 예외를 다시 던진다")
    void placeOrder_main_orderFailed_savesFailedHistory() {
        TradeHistoryDTO history = new TradeHistoryDTO();
        CustomException rejected = new CustomException(ExceptionMessage.ORDER_FAILED, "Insufficient margin");
        given(registry.trade(ExchangeType.BYBIT)).willReturn(bybitTrade);
        given(bybitTrade.placeOrder(ORDER, 1L)).willThrow(rejected);

        assertThatThrownBy(() -> tradeFacade.placeOrder(ORDER, 1L, history, ExchangeType.BYBIT, TradeMode.MAIN))
                .isSameAs(rejected);

        then(tradeHistoryService).should().saveTrade(history);
        assertThat(history.getOrderResult()).isEqualTo(OrderResult.FAILED);
        assertThat(history.getExecutedPrice()).isEqualByComparingTo("0");
        assertThat(history.getErrorMessage()).isEqualTo(rejected.getMessage());
    }

    @Test
    @DisplayName("placeOrder: 거부가 아닌 예외(통신 오류·키 없음)는 실패 이력 없이 그대로 던진다")
    void placeOrder_main_otherException_noHistory() {
        given(registry.trade(ExchangeType.BYBIT)).willReturn(bybitTrade);
        given(bybitTrade.placeOrder(ORDER, 1L))
                .willThrow(new CustomException(ExceptionMessage.API_KEY_NOT_FOUND))
                .willThrow(new RuntimeException("Read timed out"));

        assertThatThrownBy(() -> tradeFacade.placeOrder(ORDER, 1L, new TradeHistoryDTO(), ExchangeType.BYBIT, TradeMode.MAIN))
                .isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> tradeFacade.placeOrder(ORDER, 1L, new TradeHistoryDTO(), ExchangeType.BYBIT, TradeMode.MAIN))
                .hasMessage("Read timed out");
        then(tradeHistoryService).should(never()).saveTrade(any());
    }

    @Test
    @DisplayName("placeOrder: 이력 없는 주문(수동 주문)은 거부돼도 저장 없이 예외만 던진다")
    void placeOrder_main_orderFailed_withoutHistory() {
        given(registry.trade(ExchangeType.BYBIT)).willReturn(bybitTrade);
        given(bybitTrade.placeOrder(ORDER, 1L)).willThrow(new CustomException(ExceptionMessage.ORDER_FAILED, "x"));

        assertThatThrownBy(() -> tradeFacade.placeOrder(ORDER, 1L, ExchangeType.BYBIT, TradeMode.MAIN))
                .isInstanceOf(CustomException.class);
        then(tradeHistoryService).should(never()).saveTrade(any());
    }

    // ─── 마진 모드 / 레버리지 ───

    @Test
    @DisplayName("switchToIsolated / setLeverage: MAIN은 거래 클라이언트로 위임한다")
    void settings_main_delegate() {
        given(registry.trade(ExchangeType.BYBIT)).willReturn(bybitTrade);

        tradeFacade.switchToIsolated(1L, "BTCUSDT", null, ExchangeType.BYBIT, TradeMode.MAIN);
        tradeFacade.setLeverage(1L, "BTCUSDT", "10", null, ExchangeType.BYBIT, TradeMode.MAIN);

        then(bybitTrade).should().switchToIsolated(1L, "BTCUSDT");
        then(bybitTrade).should().setLeverage(1L, "BTCUSDT", "10");
    }

    @Test
    @DisplayName("cancelOrder: MAIN은 거래소 클라이언트에 위임해 취소 결과를 반환한다")
    void cancelOrder_main_delegates() {
        given(registry.trade(ExchangeType.BYBIT)).willReturn(bybitTrade);
        given(bybitTrade.cancelOrder(1L, "BTCUSDT", "q1-T-1")).willReturn(OrderCancelResult.CANCELLED);

        OrderCancelResult result = tradeFacade.cancelOrder(1L, "BTCUSDT", "q1-T-1", ExchangeType.BYBIT, TradeMode.MAIN);

        assertThat(result).isEqualTo(OrderCancelResult.CANCELLED);
    }

    @Test
    @DisplayName("cancelOrder: SIM은 걸린 주문이 없으므로 거래소 호출 없이 CANCELLED")
    void cancelOrder_sim_noop() {
        OrderCancelResult result = tradeFacade.cancelOrder(1L, "BTCUSDT", "q1-T-1", ExchangeType.BYBIT, TradeMode.SIM);

        assertThat(result).isEqualTo(OrderCancelResult.CANCELLED);
        then(registry).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("switchToIsolated / setLeverage: SIM은 아무것도 하지 않는다")
    void settings_sim_noop() {
        tradeFacade.switchToIsolated(1L, "BTCUSDT", new TradeHistoryDTO(), ExchangeType.BYBIT, TradeMode.SIM);
        tradeFacade.setLeverage(1L, "BTCUSDT", "10", new TradeHistoryDTO(), ExchangeType.BYBIT, TradeMode.SIM);

        then(registry).shouldHaveNoInteractions();
        then(tradeHistoryService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("switchToIsolated: 거래소가 거부(SWITCH_MARGIN_MODE_FAILED)하면 실패 이력을 저장하고 예외를 다시 던진다")
    void switchToIsolated_rejected_savesFailedHistory() {
        TradeHistoryDTO history = new TradeHistoryDTO();
        CustomException rejected = new CustomException(ExceptionMessage.SWITCH_MARGIN_MODE_FAILED, "[3400045] error");
        given(registry.trade(ExchangeType.BYBIT)).willReturn(bybitTrade);
        willThrow(rejected).given(bybitTrade).switchToIsolated(1L, "BTCUSDT");

        assertThatThrownBy(() -> tradeFacade.switchToIsolated(1L, "BTCUSDT", history, ExchangeType.BYBIT, TradeMode.MAIN))
                .isSameAs(rejected);

        then(tradeHistoryService).should().saveTrade(history);
        assertThat(history.getOrderResult()).isEqualTo(OrderResult.FAILED);
        assertThat(history.getExecutedPrice()).isEqualByComparingTo("0");
        assertThat(history.getErrorMessage()).isEqualTo("마진 모드 변경 실패: [3400045] error");
    }

    @Test
    @DisplayName("setLeverage: 거래소가 거부(SET_LEVERAGE_FAILED)하면 실패 이력을 저장하고 예외를 다시 던진다")
    void setLeverage_rejected_savesFailedHistory() {
        TradeHistoryDTO history = new TradeHistoryDTO();
        CustomException rejected = new CustomException(ExceptionMessage.SET_LEVERAGE_FAILED, "[10001] error");
        given(registry.trade(ExchangeType.BYBIT)).willReturn(bybitTrade);
        willThrow(rejected).given(bybitTrade).setLeverage(1L, "BTCUSDT", "10");

        assertThatThrownBy(() -> tradeFacade.setLeverage(1L, "BTCUSDT", "10", history, ExchangeType.BYBIT, TradeMode.MAIN))
                .isSameAs(rejected);

        then(tradeHistoryService).should().saveTrade(history);
        assertThat(history.getOrderResult()).isEqualTo(OrderResult.FAILED);
        assertThat(history.getErrorMessage()).isEqualTo("레버리지 설정 실패: [10001] error");
    }

    @Test
    @DisplayName("switchToIsolated / setLeverage: 거부가 아닌 예외(통신 오류)는 실패 이력 없이 그대로 던진다")
    void settings_otherException_noHistory() {
        given(registry.trade(ExchangeType.BYBIT)).willReturn(bybitTrade);
        willThrow(new RuntimeException("Read timed out")).given(bybitTrade).switchToIsolated(1L, "BTCUSDT");
        willThrow(new RuntimeException("Read timed out")).given(bybitTrade).setLeverage(1L, "BTCUSDT", "10");

        assertThatThrownBy(() -> tradeFacade.switchToIsolated(1L, "BTCUSDT", new TradeHistoryDTO(), ExchangeType.BYBIT, TradeMode.MAIN))
                .hasMessage("Read timed out");
        assertThatThrownBy(() -> tradeFacade.setLeverage(1L, "BTCUSDT", "10", new TradeHistoryDTO(), ExchangeType.BYBIT, TradeMode.MAIN))
                .hasMessage("Read timed out");
        then(tradeHistoryService).should(never()).saveTrade(any());
    }

    // ─── 체결 / 손익 / 포지션 조회 ───

    @Test
    @DisplayName("체결·손익·포지션 조회: MAIN은 거래 클라이언트 결과를 그대로 반환한다")
    void queries_main_delegate() {
        ExecutionDTO exec = ExecutionDTO.builder().execPrice("50000").build();
        ClosedPnlDTO pnl = ClosedPnlDTO.builder().closedPnl("1").build();
        PositionCloseDTO close = PositionCloseDTO.builder().orderId("c1").build();
        given(registry.trade(ExchangeType.BYBIT)).willReturn(bybitTrade);
        given(bybitTrade.expectExecution(1L, "q1-E-1")).willReturn(true);
        given(bybitTrade.getExecution("o1", "q1-E-1", "BTCUSDT", 1L)).willReturn(exec);
        ClosedPnlQueryDTO query = ClosedPnlQueryDTO.builder().exchangeType(ExchangeType.BYBIT).orderId("o1").symbol("BTCUSDT").build();
        given(bybitTrade.getClosedPnl(query, 1L)).willReturn(pnl);
        given(bybitTrade.getPositionSize(1L, "BTCUSDT")).willReturn(BigDecimal.ONE);
        given(bybitTrade.findLastClose(1L, "BTCUSDT", 1000L)).willReturn(close);

        tradeFacade.expectExecution(1L, "q1-E-1", ExchangeType.BYBIT, TradeMode.MAIN);
        assertThat(tradeFacade.getExecution("o1", "q1-E-1", "BTCUSDT", 1L, ExchangeType.BYBIT, TradeMode.MAIN)).isSameAs(exec);
        assertThat(tradeFacade.getClosedPnl(query, 1L, TradeMode.MAIN)).isSameAs(pnl);
        assertThat(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).isEqualByComparingTo("1");
        assertThat(tradeFacade.findLastClose(1L, "BTCUSDT", 1000L, ExchangeType.BYBIT, TradeMode.MAIN)).isSameAs(close);
        tradeFacade.cancelExecution("q1-E-1", ExchangeType.BYBIT, TradeMode.MAIN);

        then(bybitTrade).should().expectExecution(1L, "q1-E-1");
        then(bybitTrade).should().cancelExecution("q1-E-1");
    }

    @Test
    @DisplayName("체결·손익·포지션 조회: SIM은 거래소 데이터가 없으므로 null을 반환하고 거래소를 호출하지 않는다")
    void queries_sim_returnNull() {
        tradeFacade.expectExecution(1L, "q1-E-1", ExchangeType.BYBIT, TradeMode.SIM);
        assertThat(tradeFacade.getExecution("o1", "q1-E-1", "BTCUSDT", 1L, ExchangeType.BYBIT, TradeMode.SIM)).isNull();
        assertThat(tradeFacade.getClosedPnl(ClosedPnlQueryDTO.builder().exchangeType(ExchangeType.BYBIT).orderId("o1").symbol("BTCUSDT").build(), 1L, TradeMode.SIM)).isNull();
        assertThat(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.SIM)).isNull();
        assertThat(tradeFacade.findLastClose(1L, "BTCUSDT", 1000L, ExchangeType.BYBIT, TradeMode.SIM)).isNull();
        tradeFacade.cancelExecution("q1-E-1", ExchangeType.BYBIT, TradeMode.SIM);

        then(registry).shouldHaveNoInteractions();
    }

    // ─── 잔고 ───

    @Test
    @DisplayName("getBalance: MAIN은 거래소 타입의 실전 지갑, SIM은 가상 지갑 잔고를 반환한다")
    void getBalance_routesByMode() {
        WalletBalanceDTO main = WalletBalanceDTO.builder().totalEquity("100").build();
        WalletBalanceDTO sim = WalletBalanceDTO.builder().totalEquity("10000").build();
        given(walletService.getWalletBalance(1L, ExchangeType.BYBIT)).willReturn(main);
        given(simWalletService.getWalletBalance(1L)).willReturn(sim);

        assertThat(tradeFacade.getBalance(1L, ExchangeType.BYBIT, TradeMode.MAIN)).isSameAs(main);
        assertThat(tradeFacade.getBalance(1L, ExchangeType.BYBIT, TradeMode.SIM)).isSameAs(sim);
    }
}
