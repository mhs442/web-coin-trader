package com.coin.webcointrader.bybit.client.privatews;

import com.coin.webcointrader.bybit.client.privatews.dto.WebSocketExecutionDTO;
import com.coin.webcointrader.bybit.dto.response.GetExecutionListResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BybitExecutionWaiterTest {

    private static final Long USER_ID = 1L;
    private static final String LINK_ID = "q1-E-1000";

    @Mock
    private BybitPrivateWebSocketClient privateWebSocketClient;

    private BybitExecutionWaiter waiter;

    @BeforeEach
    void setUp() {
        waiter = new BybitExecutionWaiter(privateWebSocketClient);
        given(privateWebSocketClient.isReady(USER_ID)).willReturn(true);
    }

    @Test
    @DisplayName("전량 체결 1건을 받으면 해당 체결 정보를 반환한다")
    void singleFullFill_returnsExecution() {
        assertThat(waiter.expect(USER_ID, LINK_ID)).isTrue();

        waiter.onExecution(USER_ID, exec("e1", LINK_ID, "50000.5", "0.002", "0.055", "0"));
        GetExecutionListResponse.ExecutionInfo result = waiter.await(LINK_ID, 100);

        assertThat(result).isNotNull();
        assertThat(result.getOrderId()).isEqualTo("order-1");
        assertThat(result.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(new BigDecimal(result.getExecPrice())).isEqualByComparingTo("50000.5");
        assertThat(new BigDecimal(result.getExecQty())).isEqualByComparingTo("0.002");
        assertThat(new BigDecimal(result.getExecFee())).isEqualByComparingTo("0.055");
    }

    @Test
    @DisplayName("부분 체결은 leavesQty=0이 될 때까지 모아 수량 가중 평균가/수수료 합/수량 합으로 집계한다")
    void partialFills_aggregateUntilLeavesQtyZero() {
        waiter.expect(USER_ID, LINK_ID);

        waiter.onExecution(USER_ID, exec("e1", LINK_ID, "50000", "0.001", "0.0275", "0.003"));
        assertThat(waiter.await(LINK_ID + "-other", 10)).isNull(); // 다른 키 조회는 영향 없음
        waiter.onExecution(USER_ID, exec("e2", LINK_ID, "50100", "0.003", "0.0827", "0"));
        GetExecutionListResponse.ExecutionInfo result = waiter.await(LINK_ID, 100);

        // (50000×0.001 + 50100×0.003) / 0.004 = 50075
        assertThat(new BigDecimal(result.getExecPrice())).isEqualByComparingTo("50075");
        assertThat(new BigDecimal(result.getExecQty())).isEqualByComparingTo("0.004");
        assertThat(new BigDecimal(result.getExecFee())).isEqualByComparingTo("0.1102");
    }

    @Test
    @DisplayName("같은 execId가 중복 수신되면 한 번만 집계한다")
    void duplicateExecId_countedOnce() {
        waiter.expect(USER_ID, LINK_ID);

        waiter.onExecution(USER_ID, exec("e1", LINK_ID, "50000", "0.001", "0.0275", "0.001"));
        waiter.onExecution(USER_ID, exec("e1", LINK_ID, "50000", "0.001", "0.0275", "0.001"));
        waiter.onExecution(USER_ID, exec("e2", LINK_ID, "50000", "0.001", "0.0275", "0"));
        GetExecutionListResponse.ExecutionInfo result = waiter.await(LINK_ID, 100);

        assertThat(new BigDecimal(result.getExecQty())).isEqualByComparingTo("0.002");
        assertThat(new BigDecimal(result.getExecFee())).isEqualByComparingTo("0.055");
    }

    @Test
    @DisplayName("대기 시간 안에 전량 체결되지 않으면 null을 반환하고 대기 항목을 제거한다")
    void timeout_returnsNullAndRemoves() {
        waiter.expect(USER_ID, LINK_ID);
        waiter.onExecution(USER_ID, exec("e1", LINK_ID, "50000", "0.001", "0.0275", "0.001")); // 부분 체결만

        assertThat(waiter.await(LINK_ID, 50)).isNull();

        // 제거 후 도착한 체결은 무시되고, 다시 조회해도 결과 없음
        waiter.onExecution(USER_ID, exec("e2", LINK_ID, "50000", "0.001", "0.0275", "0"));
        assertThat(waiter.await(LINK_ID, 10)).isNull();
    }

    @Test
    @DisplayName("사용자의 private 스트림이 준비되지 않았으면 대기를 등록하지 않는다 (즉시 REST 경로)")
    void notReady_doesNotRegister() {
        given(privateWebSocketClient.isReady(USER_ID)).willReturn(false);

        assertThat(waiter.expect(USER_ID, LINK_ID)).isFalse();
        waiter.onExecution(USER_ID, exec("e1", LINK_ID, "50000", "0.002", "0.055", "0"));

        assertThat(waiter.await(LINK_ID, 10)).isNull();
    }

    @Test
    @DisplayName("등록하지 않은 orderLinkId·다른 사용자·Trade 외 체결은 무시한다")
    void ignoresUnrelatedExecutions() {
        waiter.expect(USER_ID, LINK_ID);

        waiter.onExecution(USER_ID, exec("x1", "q9-E-1", "1", "1", "1", "0"));   // 미등록 orderLinkId
        waiter.onExecution(2L, exec("x2", LINK_ID, "1", "1", "1", "0"));         // 다른 사용자
        WebSocketExecutionDTO.ExecutionData funding = exec("x3", LINK_ID, "1", "1", "1", "0");
        funding.setExecType("Funding");
        waiter.onExecution(USER_ID, funding);                                      // 펀딩비 정산

        assertThat(waiter.await(LINK_ID, 50)).isNull();
    }

    @Test
    @DisplayName("cancel하면 대기 항목을 제거한다")
    void cancel_removesPending() {
        waiter.expect(USER_ID, LINK_ID);

        waiter.cancel(LINK_ID);
        waiter.onExecution(USER_ID, exec("e1", LINK_ID, "50000", "0.002", "0.055", "0"));

        assertThat(waiter.await(LINK_ID, 10)).isNull();
    }

    private WebSocketExecutionDTO.ExecutionData exec(String execId, String orderLinkId, String price,
                                                     String qty, String fee, String leavesQty) {
        WebSocketExecutionDTO.ExecutionData d = new WebSocketExecutionDTO.ExecutionData();
        d.setSymbol("BTCUSDT");
        d.setOrderId("order-1");
        d.setOrderLinkId(orderLinkId);
        d.setExecId(execId);
        d.setSide("Buy");
        d.setExecType("Trade");
        d.setExecPrice(price);
        d.setExecQty(qty);
        d.setExecFee(fee);
        d.setLeavesQty(leavesQty);
        d.setOrderQty("0.004");
        d.setExecTime("1760950000000");
        return d;
    }
}
