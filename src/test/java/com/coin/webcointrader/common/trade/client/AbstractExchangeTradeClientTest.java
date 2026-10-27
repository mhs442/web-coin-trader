package com.coin.webcointrader.common.trade.client;

import com.coin.webcointrader.common.trade.dto.PositionCloseDTO;
import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.enums.OrderSide;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.util.MutableClock;
import com.coin.webcointrader.common.trade.dto.ClosedPnlDTO;
import com.coin.webcointrader.common.trade.dto.ClosedPnlQueryDTO;
import com.coin.webcointrader.common.trade.dto.ExecutionDTO;
import com.coin.webcointrader.common.trade.dto.OrderLookupDTO;
import com.coin.webcointrader.common.trade.dto.OrderRequestDTO;
import com.coin.webcointrader.common.trade.dto.OrderResultDTO;
import com.coin.webcointrader.common.util.AesEncryptor;
import com.coin.webcointrader.common.login.dto.ExchangeKeyDTO;
import com.coin.webcointrader.common.login.service.ExchangeKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.LinkedList;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * 거래 클라이언트 공통 처리(API 키 바인딩·설정 캐시·주문 복구·체결 집계·손익 조회) 단위 테스트.
 */
@ExtendWith(MockitoExtension.class)
class AbstractExchangeTradeClientTest {

    private static final long TTL = 10 * 60 * 1000L;

    @Mock
    private ExchangeKeyService exchangeKeyService;

    @Mock
    private AesEncryptor aesEncryptor;

    private MutableClock clock;
    private FakeTradeClient client;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(10_000_000L);
        client = new FakeTradeClient(exchangeKeyService, aesEncryptor, clock);
    }

    private void givenKey() {
        given(exchangeKeyService.findKey(1L, ExchangeType.BYBIT)).willReturn(
                ExchangeKeyDTO.builder().userId(1L).exchangeType(ExchangeType.BYBIT).apiKey("encK").apiSecret("encS").build());
        given(aesEncryptor.decrypt(anyString())).willAnswer(inv -> "plain-" + inv.getArgument(0));
    }

    private static OrderRequestDTO order(String clientOrderId) {
        return OrderRequestDTO.builder().symbol("BTCUSDT").side(OrderSide.BUY).qty("0.01").clientOrderId(clientOrderId).build();
    }

    // ─── API 키 바인딩 ───

    @Test
    @DisplayName("placeOrder - 거래소 타입의 키를 복호화해 바인딩하고, 끝나면 해제")
    void placeOrder_bindsAndUnbindsKey() {
        givenKey();
        client.submitResult = OrderResultDTO.builder().orderId("o1").build();

        OrderResultDTO result = client.placeOrder(order("c1"), 1L);

        assertThat(result.getOrderId()).isEqualTo("o1");
        assertThat(client.boundDuringSubmit).isEqualTo("plain-encK/plain-encS");
        assertThat(client.bound).isNull();
    }

    @Test
    @DisplayName("placeOrder - 예외가 나도 키 바인딩 해제")
    void placeOrder_unbindsOnException() {
        givenKey();
        client.submitError = new CustomException(ExceptionMessage.ORDER_FAILED, "rejected");

        assertThatThrownBy(() -> client.placeOrder(order("c1"), 1L)).isInstanceOf(CustomException.class);
        assertThat(client.bound).isNull();
    }

    // ─── 주문 결과 불명확 복구 ───

    @Test
    @DisplayName("placeOrder - 결과 불명확 실패 후 조회로 접수 확인되면 복구 결과 반환")
    void placeOrder_ambiguousRecovered() {
        givenKey();
        client.submitError = new AmbiguousException();
        client.lookups.add(null); // 1회차 미반영
        client.lookups.add(OrderLookupDTO.builder().orderId("o9").accepted(true).build());

        OrderResultDTO result = client.placeOrder(order("c1"), 1L);

        assertThat(result.getOrderId()).isEqualTo("o9");
        assertThat(result.getClientOrderId()).isEqualTo("c1");
    }

    @Test
    @DisplayName("placeOrder - 결과 불명확 실패 후 거부 확인되면 원래 예외")
    void placeOrder_ambiguousRejected() {
        givenKey();
        AmbiguousException original = new AmbiguousException();
        client.submitError = original;
        client.lookups.add(OrderLookupDTO.builder().orderId("o9").accepted(false).build());

        assertThatThrownBy(() -> client.placeOrder(order("c1"), 1L)).isSameAs(original);
    }

    @Test
    @DisplayName("placeOrder - clientOrderId가 없으면 조회 없이 원래 예외")
    void placeOrder_ambiguousWithoutClientOrderId() {
        givenKey();
        AmbiguousException original = new AmbiguousException();
        client.submitError = original;

        assertThatThrownBy(() -> client.placeOrder(order(null), 1L)).isSameAs(original);
        assertThat(client.lookupCount).isZero();
    }

    @Test
    @DisplayName("placeOrder - 불명확하지 않은 실패는 조회하지 않고 그대로 던짐")
    void placeOrder_nonAmbiguousRethrown() {
        givenKey();
        client.submitError = new CustomException(ExceptionMessage.ORDER_FAILED, "rejected");

        assertThatThrownBy(() -> client.placeOrder(order("c1"), 1L)).isInstanceOf(CustomException.class);
        assertThat(client.lookupCount).isZero();
    }

    // ─── 설정 캐시 ───

    @Test
    @DisplayName("switchToIsolated - 유효 시간 내 재호출은 거래소 호출 생략, 만료 후 다시 호출")
    void switchToIsolated_cached() {
        givenKey();

        client.switchToIsolated(1L, "BTCUSDT");
        clock.advance(TTL);
        client.switchToIsolated(1L, "BTCUSDT");
        assertThat(client.isolatedCalls).isEqualTo(1);

        clock.advance(1);
        client.switchToIsolated(1L, "BTCUSDT");
        assertThat(client.isolatedCalls).isEqualTo(2);
    }

    @Test
    @DisplayName("switchToIsolated - 심볼이 다르면 캐시와 별개로 다시 호출하고, 받은 심볼을 그대로 넘긴다")
    void switchToIsolated_cachedPerSymbol() {
        givenKey();

        client.switchToIsolated(1L, "BTCUSDT");
        client.switchToIsolated(1L, "ETHUSDT");

        // Binance 등 심볼 단위 거래소는 심볼마다 설정해야 하므로 각각 호출
        assertThat(client.isolatedCalls).isEqualTo(2);
        assertThat(client.lastIsolatedSymbol).isEqualTo("ETHUSDT");
    }

    @Test
    @DisplayName("switchToIsolated - 실패하면 캐시하지 않음")
    void switchToIsolated_failureNotCached() {
        givenKey();
        client.isolatedError = new CustomException(ExceptionMessage.SWITCH_MARGIN_MODE_FAILED, "x");
        assertThatThrownBy(() -> client.switchToIsolated(1L, "BTCUSDT")).isInstanceOf(CustomException.class);

        client.isolatedError = null;
        client.switchToIsolated(1L, "BTCUSDT");
        assertThat(client.isolatedCalls).isEqualTo(2);
    }

    @Test
    @DisplayName("setLeverage - 같은 값은 캐시, 다른 값이나 다른 심볼은 다시 호출")
    void setLeverage_cachedPerValue() {
        givenKey();

        client.setLeverage(1L, "BTCUSDT", "10");
        client.setLeverage(1L, "BTCUSDT", "10");
        assertThat(client.leverageCalls).containsExactly("BTCUSDT:10");

        client.setLeverage(1L, "BTCUSDT", "5");
        client.setLeverage(1L, "ETHUSDT", "5");
        assertThat(client.leverageCalls).containsExactly("BTCUSDT:10", "BTCUSDT:5", "ETHUSDT:5");
    }

    // ─── 체결 조회 ───

    @Test
    @DisplayName("getExecution - 스트림 체결이 오면 REST 조회 생략")
    void getExecution_streamFirst() {
        client.streamExecution = ExecutionDTO.builder().execPrice("100").build();

        ExecutionDTO result = client.getExecution("o1", "c1", "BTCUSDT", 1L);

        assertThat(result.getExecPrice()).isEqualTo("100");
        assertThat(client.executionFetchCount).isZero();
    }

    @Test
    @DisplayName("getExecution - REST 체결을 수량 가중 평균가·수수료 합·수량 합으로 집계")
    void getExecution_aggregates() {
        givenKey();
        client.executions.add(List.of(
                exec("100", "1", "0.1", "3"),
                exec("103", "2", "0.2", "3")));

        ExecutionDTO result = client.getExecution("o1", "c1", "BTCUSDT", 1L);

        // (100×1 + 103×2) / 3 = 102
        assertThat(result.getExecPrice()).isEqualTo("102");
        assertThat(result.getExecQty()).isEqualTo("3");
        assertThat(result.getExecFee()).isEqualTo("0.3");
        assertThat(result.getOrderId()).isEqualTo("o1");
        assertThat(client.executionFetchCount).isEqualTo(1);
    }

    @Test
    @DisplayName("getExecution - 일부만 반영되면 재조회, 그래도 일부면 반영분 반환")
    void getExecution_partialRetried() {
        givenKey();
        client.executions.add(List.of(exec("100", "1", "0.1", "3")));
        client.executions.add(List.of(exec("100", "1", "0.1", "3"), exec("100", "1", "0.1", "3")));

        ExecutionDTO result = client.getExecution("o1", "c1", "BTCUSDT", 1L);

        assertThat(result.getExecQty()).isEqualTo("2");
        assertThat(client.executionFetchCount).isEqualTo(2);
    }

    @Test
    @DisplayName("getExecution - 체결 레코드가 끝내 없으면 null")
    void getExecution_noneReturnsNull() {
        givenKey();

        assertThat(client.getExecution("o1", "c1", "BTCUSDT", 1L)).isNull();
    }

    // ─── 청산 손익 ───

    private static final ClosedPnlQueryDTO QUERY = ClosedPnlQueryDTO.builder()
            .orderId("o1").symbol("BTCUSDT").build();

    @Test
    @DisplayName("getClosedPnl - 거래소 구현이 찾은 청산 손익을 바로 반환한다")
    void getClosedPnl_found() {
        givenKey();
        ClosedPnlDTO found = ClosedPnlDTO.builder().orderId("o1").closedPnl("7").fundingFee("1").build();
        client.closedPnls.add(found);

        assertThat(client.getClosedPnl(QUERY, 1L)).isSameAs(found);
        assertThat(client.closedPnlFetchCount).isEqualTo(1);
    }

    @Test
    @DisplayName("getClosedPnl - 에러·미반영(null)이 계속되면 2회 조회 후 null")
    void getClosedPnl_notFound() {
        givenKey();

        assertThat(client.getClosedPnl(QUERY, 1L)).isNull();
        assertThat(client.closedPnlFetchCount).isEqualTo(2);
    }

    // ─── 청산 리스너 ───

    @Test
    @DisplayName("publishPositionClose - 등록된 리스너 모두에 전달, 예외는 격리")
    void publishPositionClose_fanOut() {
        List<String> received = new ArrayList<>();
        client.addPositionCloseListener(e -> { throw new IllegalStateException("boom"); });
        client.addPositionCloseListener(e -> received.add(e.getOrderId()));

        client.publish(PositionCloseDTO.builder().orderId("o1").build());

        assertThat(received).containsExactly("o1");
    }

    private static ExecutionDTO exec(String price, String qty, String fee, String orderQty) {
        return ExecutionDTO.builder().symbol("BTCUSDT").orderId("o1").side(OrderSide.BUY)
                .execPrice(price).execQty(qty).execFee(fee).orderQty(orderQty).execTime("1").build();
    }

    /** 결과 불명확 실패를 흉내 내는 예외 */
    static class AmbiguousException extends RuntimeException { }

    /** 테스트용 거래 클라이언트 (거래소 호출을 필드 값으로 대체) */
    static class FakeTradeClient extends AbstractExchangeTradeClient {
        String bound;
        String boundDuringSubmit;
        OrderResultDTO submitResult;
        RuntimeException submitError;
        Deque<OrderLookupDTO> lookups = new LinkedList<>();
        int lookupCount;
        int isolatedCalls;
        RuntimeException isolatedError;
        String lastIsolatedSymbol;
        List<String> leverageCalls = new ArrayList<>();
        ExecutionDTO streamExecution;
        Deque<List<ExecutionDTO>> executions = new LinkedList<>();
        int executionFetchCount;
        Deque<ClosedPnlDTO> closedPnls = new LinkedList<>();
        int closedPnlFetchCount;

        FakeTradeClient(ExchangeKeyService keyService, AesEncryptor encryptor, MutableClock clock) {
            super(keyService, encryptor, clock);
        }

        void publish(PositionCloseDTO e) { publishPositionClose(e); }

        @Override public ExchangeType getExchangeType() { return ExchangeType.BYBIT; }
        @Override public BigDecimal getTakerFeeRate() { return new BigDecimal("0.00055"); }

        @Override protected void bindApiKey(String apiKey, String apiSecret) { bound = apiKey + "/" + apiSecret; }
        @Override protected void unbindApiKey() { bound = null; }
        @Override protected void sleep(long millis) { }

        @Override protected OrderResultDTO submitOrder(OrderRequestDTO request) {
            boundDuringSubmit = bound;
            if (submitError != null) throw submitError;
            return submitResult;
        }

        @Override protected boolean isAmbiguousFailure(RuntimeException e) { return e instanceof AmbiguousException; }

        @Override protected OrderLookupDTO lookupOrder(OrderRequestDTO request) {
            lookupCount++;
            return lookups.isEmpty() ? null : lookups.poll();
        }

        @Override protected void doSwitchToIsolated(String symbol) {
            isolatedCalls++;
            lastIsolatedSymbol = symbol;
            if (isolatedError != null) throw isolatedError;
        }

        @Override protected void doSetLeverage(String symbol, String leverage) { leverageCalls.add(symbol + ":" + leverage); }

        @Override protected ExecutionDTO awaitExecution(String clientOrderId, long waitMillis) { return streamExecution; }

        @Override protected List<ExecutionDTO> fetchExecutions(String orderId, String symbol) {
            executionFetchCount++;
            return executions.isEmpty() ? List.of() : executions.poll();
        }

        @Override protected ClosedPnlDTO fetchClosedPnl(ClosedPnlQueryDTO query) {
            closedPnlFetchCount++;
            return closedPnls.isEmpty() ? null : closedPnls.poll();
        }

        @Override public boolean expectExecution(Long userId, String clientOrderId) { return false; }
        @Override public void cancelExecution(String clientOrderId) { }
        @Override public PositionCloseDTO findLastClose(Long userId, String symbol, long sinceMillis) { return null; }
        @Override public BigDecimal getPositionSize(Long userId, String symbol) { return null; }
        @Override public void syncPrivateStreams(Set<Long> userIds) { }
    }
}
