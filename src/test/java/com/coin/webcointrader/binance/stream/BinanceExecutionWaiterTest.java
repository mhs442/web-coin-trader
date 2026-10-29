package com.coin.webcointrader.binance.stream;

import com.coin.webcointrader.binance.stream.dto.BinanceFilledOrderDTO;
import com.coin.webcointrader.binance.stream.dto.BinanceOrderTradeUpdateDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

/**
 * Binance 체결 대기자 단위 테스트.
 * ORDER_TRADE_UPDATE 체결 건을 clientOrderId 별로 모아 전량 체결(또는 부분 체결 후 종료) 시 집계 결과를 돌려주는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BinanceExecutionWaiterTest {

    private static final Long USER_ID = 1L;
    private static final String CLIENT_ORDER_ID = "q12-E-1760950000000";

    @Mock
    private BinancePrivateWebSocketClient privateWebSocketClient;

    private BinanceExecutionWaiter waiter;

    @BeforeEach
    void setUp() {
        waiter = new BinanceExecutionWaiter(privateWebSocketClient);
        given(privateWebSocketClient.isReady(USER_ID)).willReturn(true);
    }

    /** 체결 1건(x=TRADE) 이벤트 */
    private static BinanceOrderTradeUpdateDTO.OrderUpdate fill(String tradeId, String price, String qty, String cumQty,
                                                               String fee, String feeAsset, String status) {
        BinanceOrderTradeUpdateDTO.OrderUpdate o = new BinanceOrderTradeUpdateDTO.OrderUpdate();
        o.setSymbol("BTCUSDT");
        o.setClientOrderId(CLIENT_ORDER_ID);
        o.setOrderId("8886774");
        o.setSide("BUY");
        o.setOrigQty("0.020");
        o.setExecType("TRADE");
        o.setOrderStatus(status);
        o.setTradeId(tradeId);
        o.setLastFilledPrice(price);
        o.setLastFilledQty(qty);
        o.setCumFilledQty(cumQty);
        o.setCommission(fee);
        o.setCommissionAsset(feeAsset);
        o.setTradeTime(1568879465650L);
        return o;
    }

    /** 체결 없는 상태 변경 이벤트 (x=NEW/EXPIRED/CANCELED) */
    private static BinanceOrderTradeUpdateDTO.OrderUpdate statusEvent(String execType, String status, String cumQty) {
        BinanceOrderTradeUpdateDTO.OrderUpdate o = new BinanceOrderTradeUpdateDTO.OrderUpdate();
        o.setSymbol("BTCUSDT");
        o.setClientOrderId(CLIENT_ORDER_ID);
        o.setOrderId("8886774");
        o.setExecType(execType);
        o.setOrderStatus(status);
        o.setCumFilledQty(cumQty);
        o.setLastFilledQty("0");
        return o;
    }

    @Test
    @DisplayName("부분 체결 2건 + FILLED → 수량 가중 평균가·수량 합·수수료 합으로 집계한다")
    void aggregatesPartialFills() {
        waiter.expect(USER_ID, CLIENT_ORDER_ID);

        waiter.onOrderUpdate(USER_ID, statusEvent("NEW", "NEW", "0"));
        waiter.onOrderUpdate(USER_ID, fill("t-1", "50000", "0.010", "0.010", "0.25", "USDT", "PARTIALLY_FILLED"));
        waiter.onOrderUpdate(USER_ID, fill("t-2", "50010", "0.010", "0.020", "0.25005", "USDT", "FILLED"));

        BinanceFilledOrderDTO result = waiter.await(CLIENT_ORDER_ID, 1000);

        assertThat(result).isNotNull();
        assertThat(result.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(result.getOrderId()).isEqualTo("8886774");
        assertThat(result.getSide()).isEqualTo("BUY");
        assertThat(result.getOrderQty()).isEqualTo("0.020");
        assertThat(result.getAvgPrice()).isEqualTo("50005");
        assertThat(result.getFilledQty()).isEqualTo("0.02");
        assertThat(result.getUsdtFee()).isEqualTo("0.50005");
        assertThat(result.getBnbFee()).isEqualTo("0");
        assertThat(result.isOtherFeeAsset()).isFalse();
        assertThat(result.getExecTime()).isEqualTo("1568879465650");
    }

    @Test
    @DisplayName("같은 체결 ID가 다시 와도 한 번만 집계한다")
    void deduplicatesByTradeId() {
        waiter.expect(USER_ID, CLIENT_ORDER_ID);

        waiter.onOrderUpdate(USER_ID, fill("t-1", "50000", "0.010", "0.010", "0.25", "USDT", "PARTIALLY_FILLED"));
        waiter.onOrderUpdate(USER_ID, fill("t-1", "50000", "0.010", "0.010", "0.25", "USDT", "PARTIALLY_FILLED"));
        waiter.onOrderUpdate(USER_ID, fill("t-2", "50000", "0.010", "0.020", "0.25", "USDT", "FILLED"));

        BinanceFilledOrderDTO result = waiter.await(CLIENT_ORDER_ID, 1000);

        assertThat(result.getFilledQty()).isEqualTo("0.02");
        assertThat(result.getUsdtFee()).isEqualTo("0.5");
    }

    @Test
    @DisplayName("수수료는 USDT / BNB 로 나눠 집계하고, 그 외 자산이 있으면 표시한다 (환산은 호출부)")
    void separatesFeeByAsset() {
        waiter.expect(USER_ID, CLIENT_ORDER_ID);

        waiter.onOrderUpdate(USER_ID, fill("t-1", "50000", "0.010", "0.010", "0.25", "USDT", "PARTIALLY_FILLED"));
        waiter.onOrderUpdate(USER_ID, fill("t-2", "50000", "0.005", "0.015", "0.0003", "BNB", "PARTIALLY_FILLED"));
        waiter.onOrderUpdate(USER_ID, fill("t-3", "50000", "0.005", "0.020", "0.1", "BFUSD", "FILLED"));

        BinanceFilledOrderDTO result = waiter.await(CLIENT_ORDER_ID, 1000);

        assertThat(result.getUsdtFee()).isEqualTo("0.25");
        assertThat(result.getBnbFee()).isEqualTo("0.0003");
        assertThat(result.isOtherFeeAsset()).isTrue();
    }

    @Test
    @DisplayName("부분 체결 후 EXPIRED 로 끝나면 그때까지 체결된 양으로 완료한다")
    void partialFillThenExpired_completesWithFilledPart() {
        waiter.expect(USER_ID, CLIENT_ORDER_ID);

        waiter.onOrderUpdate(USER_ID, fill("t-1", "50000", "0.010", "0.010", "0.25", "USDT", "PARTIALLY_FILLED"));
        waiter.onOrderUpdate(USER_ID, statusEvent("EXPIRED", "EXPIRED", "0.010"));

        BinanceFilledOrderDTO result = waiter.await(CLIENT_ORDER_ID, 1000);

        assertThat(result).isNotNull();
        assertThat(result.getFilledQty()).isEqualTo("0.01");
        assertThat(result.getOrderQty()).isEqualTo("0.020");
    }

    @Test
    @DisplayName("체결 없이 EXPIRED/CANCELED 로 끝나면 대기 시간을 채우지 않고 즉시 null 을 돌려준다")
    void noFillThenExpired_returnsNullImmediately() {
        waiter.expect(USER_ID, CLIENT_ORDER_ID);
        waiter.onOrderUpdate(USER_ID, statusEvent("EXPIRED", "EXPIRED", "0"));

        long start = System.currentTimeMillis();
        BinanceFilledOrderDTO result = waiter.await(CLIENT_ORDER_ID, 2000);

        assertThat(result).isNull();
        assertThat(System.currentTimeMillis() - start).isLessThan(1000);
    }

    @Test
    @DisplayName("대기 시간 안에 전량 체결되지 않으면 null 을 돌려준다 (호출부는 REST 조회)")
    void timeout_returnsNull() {
        waiter.expect(USER_ID, CLIENT_ORDER_ID);
        waiter.onOrderUpdate(USER_ID, fill("t-1", "50000", "0.010", "0.010", "0.25", "USDT", "PARTIALLY_FILLED"));

        assertThat(waiter.await(CLIENT_ORDER_ID, 100)).isNull();
    }

    @Test
    @DisplayName("다른 스레드에서 체결이 들어오면 대기 중인 await 가 결과를 받는다")
    void completesWhileAwaiting() throws Exception {
        waiter.expect(USER_ID, CLIENT_ORDER_ID);

        CompletableFuture<BinanceFilledOrderDTO> awaiting =
                CompletableFuture.supplyAsync(() -> waiter.await(CLIENT_ORDER_ID, 2000));
        Thread.sleep(50);
        waiter.onOrderUpdate(USER_ID, fill("t-1", "50000", "0.020", "0.020", "0.5", "USDT", "FILLED"));

        assertThat(awaiting.get(1, TimeUnit.SECONDS).getFilledQty()).isEqualTo("0.02");
    }

    @Test
    @DisplayName("스트림이 준비되지 않았으면 등록하지 않고, await 는 즉시 null")
    void notReady_doesNotRegister() {
        given(privateWebSocketClient.isReady(USER_ID)).willReturn(false);

        assertThat(waiter.expect(USER_ID, CLIENT_ORDER_ID)).isFalse();
        assertThat(waiter.await(CLIENT_ORDER_ID, 2000)).isNull();
    }

    @Test
    @DisplayName("다른 사용자 연결에서 온 체결과 미등록 주문의 체결은 무시한다")
    void ignoresOtherUserAndUnknownOrder() {
        given(privateWebSocketClient.isReady(2L)).willReturn(true);
        waiter.expect(USER_ID, CLIENT_ORDER_ID);

        waiter.onOrderUpdate(2L, fill("t-1", "50000", "0.020", "0.020", "0.5", "USDT", "FILLED"));
        BinanceOrderTradeUpdateDTO.OrderUpdate unknown =
                fill("t-2", "50000", "0.020", "0.020", "0.5", "USDT", "FILLED");
        unknown.setClientOrderId("q99-E-1");
        waiter.onOrderUpdate(USER_ID, unknown);

        assertThat(waiter.await(CLIENT_ORDER_ID, 100)).isNull();
    }

    @Test
    @DisplayName("cancel 하면 대기 항목을 지워 이후 체결이 와도 집계하지 않는다")
    void cancel_removesPending() {
        waiter.expect(USER_ID, CLIENT_ORDER_ID);
        waiter.cancel(CLIENT_ORDER_ID);

        waiter.onOrderUpdate(USER_ID, fill("t-1", "50000", "0.020", "0.020", "0.5", "USDT", "FILLED"));

        assertThat(waiter.await(CLIENT_ORDER_ID, 100)).isNull();
    }

    @Test
    @DisplayName("init 시 개인 스트림에 주문 업데이트 콜백을 등록한다")
    void init_registersCallback() {
        waiter.init();

        then(privateWebSocketClient).should().addOrderUpdateCallback(any());
    }
}
