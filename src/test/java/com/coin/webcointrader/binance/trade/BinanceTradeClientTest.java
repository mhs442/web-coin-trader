package com.coin.webcointrader.binance.trade;

import com.coin.webcointrader.binance.common.BinanceApiException;
import com.coin.webcointrader.binance.market.BinanceMarketClient;
import com.coin.webcointrader.binance.trade.dto.BinanceAlgoOrderRequest;
import com.coin.webcointrader.binance.trade.dto.BinanceAlgoOrderResponse;
import com.coin.webcointrader.binance.trade.dto.BinanceIncomeResponse;
import com.coin.webcointrader.binance.trade.dto.BinanceOrderRequest;
import com.coin.webcointrader.binance.trade.dto.BinanceOrderResponse;
import com.coin.webcointrader.binance.trade.dto.BinancePositionRiskResponse;
import com.coin.webcointrader.binance.trade.dto.BinanceUserTradeResponse;
import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.enums.OrderSide;
import com.coin.webcointrader.common.enums.PositionCloseType;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.login.dto.ExchangeKeyDTO;
import com.coin.webcointrader.common.login.service.ExchangeKeyService;
import com.coin.webcointrader.common.market.dto.TickerDTO;
import com.coin.webcointrader.common.trade.dto.ClosedPnlDTO;
import com.coin.webcointrader.common.trade.dto.ClosedPnlQueryDTO;
import com.coin.webcointrader.common.trade.dto.ExecutionDTO;
import com.coin.webcointrader.common.trade.dto.OrderRequestDTO;
import com.coin.webcointrader.common.trade.dto.OrderResultDTO;
import com.coin.webcointrader.common.trade.dto.PositionCloseDTO;
import com.coin.webcointrader.common.util.AesEncryptor;
import com.coin.webcointrader.common.util.UserApiKeyContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Request;
import feign.RetryableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.BDDMockito.willThrow;

/**
 * Binance 거래 클라이언트 단위 테스트.
 * Feign 응답은 __files/BinanceTradeClient_*.json fixture로 모의하고,
 * 주문 변환·결과 불명 복구·조건부 주문 정리·체결/손익 집계·청산 감지를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class BinanceTradeClientTest {

    private static final String FILES = "src/test/resources/__files/";
    private static final String CLIENT_ORDER_ID = "q1-E-1761526800000";
    private static final long NOW = 1761530400000L;

    @Mock
    private BinanceTradeFeignClient feignClient;

    @Mock
    private BinanceMarketClient marketClient;

    @Mock
    private ExchangeKeyService exchangeKeyService;

    @Mock
    private AesEncryptor aesEncryptor;

    @Mock
    private Clock clock;

    private BinanceTradeClient client;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // 재시도 대기 없이 실행
        client = new BinanceTradeClient(feignClient, marketClient, exchangeKeyService, aesEncryptor, clock) {
            @Override
            protected void sleep(long millis) {
            }
        };
    }

    @AfterEach
    void clearContext() {
        UserApiKeyContext.clear();
    }

    // ─────────────────────────────────────────────
    // placeOrder: 요청 변환·조건부 주문 정리
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("placeOrder 진입: 남은 조건부 주문을 먼저 취소하고 시장가 주문을 보낸다 (reduceOnly 생략)")
    void placeOrder_entry() throws Exception {
        givenKey();
        given(feignClient.newOrder(any())).willReturn(fixture("newOrder", BinanceOrderResponse.class));

        OrderResultDTO result = client.placeOrder(entry(null, null), 1L);

        assertThat(result.getOrderId()).isEqualTo("22542179");
        assertThat(result.getClientOrderId()).isEqualTo(CLIENT_ORDER_ID);
        InOrder order = inOrder(feignClient);
        order.verify(feignClient).cancelAllAlgoOpenOrders("BTCUSDT");
        ArgumentCaptor<BinanceOrderRequest> captor = ArgumentCaptor.forClass(BinanceOrderRequest.class);
        order.verify(feignClient).newOrder(captor.capture());
        BinanceOrderRequest sent = captor.getValue();
        assertThat(sent.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(sent.getSide()).isEqualTo("BUY");
        assertThat(sent.getType()).isEqualTo("MARKET");
        assertThat(sent.getQuantity()).isEqualTo("0.010");
        assertThat(sent.getNewClientOrderId()).isEqualTo(CLIENT_ORDER_ID);
        assertThat(sent.getReduceOnly()).isNull();
        then(feignClient).should(never()).newAlgoOrder(any());
        // 키 컨텍스트 정리
        assertThat(UserApiKeyContext.getApiKey()).isNull();
    }

    @Test
    @DisplayName("placeOrder 청산: reduceOnly=true로 보내고, 접수 후 남은 조건부 주문을 취소한다")
    void placeOrder_close() throws Exception {
        givenKey();
        given(feignClient.newOrder(any())).willReturn(fixture("newOrder", BinanceOrderResponse.class));
        OrderRequestDTO close = OrderRequestDTO.builder()
                .symbol("BTCUSDT").side(OrderSide.SELL).qty("0.010").reduceOnly(true).clientOrderId("q1-S-1").build();

        client.placeOrder(close, 1L);

        InOrder order = inOrder(feignClient);
        ArgumentCaptor<BinanceOrderRequest> captor = ArgumentCaptor.forClass(BinanceOrderRequest.class);
        order.verify(feignClient).newOrder(captor.capture());
        order.verify(feignClient).cancelAllAlgoOpenOrders("BTCUSDT");
        assertThat(captor.getValue().getSide()).isEqualTo("SELL");
        assertThat(captor.getValue().getReduceOnly()).isEqualTo("true");
    }

    @Test
    @DisplayName("placeOrder TP/SL: 진입 접수 후 반대 방향 포지션 전체 청산 조건부 주문 2건을 최종 체결가 기준으로 보낸다")
    void placeOrder_withTpSl() throws Exception {
        givenKey();
        given(feignClient.newOrder(any())).willReturn(fixture("newOrder", BinanceOrderResponse.class));
        given(feignClient.newAlgoOrder(any())).willReturn(fixture("newAlgoOrder", BinanceAlgoOrderResponse.class));

        client.placeOrder(entry("52000", "48000"), 1L);

        ArgumentCaptor<BinanceAlgoOrderRequest> captor = ArgumentCaptor.forClass(BinanceAlgoOrderRequest.class);
        then(feignClient).should(times(2)).newAlgoOrder(captor.capture());
        BinanceAlgoOrderRequest tp = captor.getAllValues().get(0);
        BinanceAlgoOrderRequest sl = captor.getAllValues().get(1);
        assertThat(tp.getAlgoType()).isEqualTo("CONDITIONAL");
        assertThat(tp.getType()).isEqualTo("TAKE_PROFIT_MARKET");
        assertThat(tp.getSide()).isEqualTo("SELL");
        assertThat(tp.getTriggerPrice()).isEqualTo("52000");
        assertThat(tp.getClosePosition()).isEqualTo("true");
        assertThat(tp.getWorkingType()).isEqualTo("CONTRACT_PRICE");
        assertThat(tp.getClientAlgoId()).isEqualTo(CLIENT_ORDER_ID + "-TP");
        assertThat(sl.getType()).isEqualTo("STOP_MARKET");
        assertThat(sl.getSide()).isEqualTo("SELL");
        assertThat(sl.getTriggerPrice()).isEqualTo("48000");
        assertThat(sl.getClientAlgoId()).isEqualTo(CLIENT_ORDER_ID + "-SL");
    }

    @Test
    @DisplayName("placeOrder TP/SL 접수 실패: 포지션은 이미 열렸으므로 진입은 성공으로 반환한다")
    void placeOrder_tpSlFailureKeepsEntry() throws Exception {
        givenKey();
        given(feignClient.newOrder(any())).willReturn(fixture("newOrder", BinanceOrderResponse.class));
        given(feignClient.newAlgoOrder(any())).willThrow(new BinanceApiException(-2021, "Order would immediately trigger."));

        OrderResultDTO result = client.placeOrder(entry("52000", "48000"), 1L);

        assertThat(result.getOrderId()).isEqualTo("22542179");
        // TP 실패가 SL 접수를 막지 않는다
        then(feignClient).should(times(2)).newAlgoOrder(any());
    }

    @Test
    @DisplayName("placeOrder 진입: 조건부 주문 취소가 실패해도 진입 주문은 보낸다")
    void placeOrder_cancelFailureStillOrders() throws Exception {
        givenKey();
        willThrow(new BinanceApiException(-1001, "Internal error")).given(feignClient).cancelAllAlgoOpenOrders("BTCUSDT");
        given(feignClient.newOrder(any())).willReturn(fixture("newOrder", BinanceOrderResponse.class));

        assertThat(client.placeOrder(entry(null, null), 1L).getOrderId()).isEqualTo("22542179");
    }

    // ─────────────────────────────────────────────
    // placeOrder: 거부 확정 / 결과 불명 복구
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("placeOrder 거부: 거래소 코드·메시지로 CustomException(ORDER_FAILED) 발생, 주문 조회는 하지 않는다")
    void placeOrder_rejected() {
        givenKey();
        given(feignClient.newOrder(any())).willThrow(new BinanceApiException(-2019, "Margin is insufficient."));

        assertThatThrownBy(() -> client.placeOrder(entry(null, null), 1L))
                .isInstanceOf(CustomException.class)
                .hasMessage(ExceptionMessage.ORDER_FAILED.getMessage() + ": [-2019] Margin is insufficient.");
        then(feignClient).should(never()).getOrder(anyString(), any(), any());
    }

    @Test
    @DisplayName("placeOrder 결과 불명(-1000 Unknown error): clientOrderId로 조회해 체결이 확인되면 성공으로 복구한다")
    void placeOrder_unknownRecovered() throws Exception {
        givenKey();
        given(feignClient.newOrder(any())).willThrow(new BinanceApiException(-1000, "Unknown error, please check your request or try again later."));
        given(feignClient.getOrder("BTCUSDT", null, CLIENT_ORDER_ID)).willReturn(fixture("getOrder_filled", BinanceOrderResponse.class));

        OrderResultDTO result = client.placeOrder(entry(null, null), 1L);

        assertThat(result.getOrderId()).isEqualTo("22542179");
    }

    @Test
    @DisplayName("placeOrder 결과 불명: 조회해도 주문이 없으면(-2013) 재조회 후 원래 예외를 전파한다")
    void placeOrder_unknownNotFound() {
        givenKey();
        BinanceApiException timeout = new BinanceApiException(-1007, "Timeout waiting for response from backend server.");
        given(feignClient.newOrder(any())).willThrow(timeout);
        given(feignClient.getOrder("BTCUSDT", null, CLIENT_ORDER_ID)).willThrow(new BinanceApiException(-2013, "Order does not exist."));

        assertThatThrownBy(() -> client.placeOrder(entry(null, null), 1L)).isSameAs(timeout);
        then(feignClient).should(times(2)).getOrder("BTCUSDT", null, CLIENT_ORDER_ID);
    }

    @Test
    @DisplayName("placeOrder 결과 불명: 조회 결과가 체결 없이 만료(EXPIRED)면 실패로 확정한다")
    void placeOrder_unknownExpired() throws Exception {
        givenKey();
        RetryableException timeout = makeTimeoutException();
        given(feignClient.newOrder(any())).willThrow(timeout);
        given(feignClient.getOrder("BTCUSDT", null, CLIENT_ORDER_ID)).willReturn(fixture("getOrder_expired", BinanceOrderResponse.class));

        assertThatThrownBy(() -> client.placeOrder(entry(null, null), 1L)).isSameAs(timeout);
        then(feignClient).should(times(1)).getOrder("BTCUSDT", null, CLIENT_ORDER_ID);
    }

    @Test
    @DisplayName("isAmbiguousFailure: 타임아웃·-1000·-1006·-1007·본문 없는 5xx는 결과 불명, 그 외는 거부 확정")
    void isAmbiguousFailure() {
        assertThat(client.isAmbiguousFailure(makeTimeoutException())).isTrue();
        assertThat(client.isAmbiguousFailure(new BinanceApiException(-1000, "Unknown error"))).isTrue();
        assertThat(client.isAmbiguousFailure(new BinanceApiException(-1006, "Unexpected response"))).isTrue();
        assertThat(client.isAmbiguousFailure(new BinanceApiException(-1007, "Timeout"))).isTrue();
        assertThat(client.isAmbiguousFailure(new BinanceApiException(503, "Service Unavailable"))).isTrue();
        assertThat(client.isAmbiguousFailure(new BinanceApiException(-2019, "Margin is insufficient."))).isFalse();
        assertThat(client.isAmbiguousFailure(new BinanceApiException(400, "Bad Request"))).isFalse();
        assertThat(client.isAmbiguousFailure(new IllegalStateException("x"))).isFalse();
    }

    // ─────────────────────────────────────────────
    // 마진 모드 / 레버리지
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("switchToIsolated: 심볼 단위로 ISOLATED 전환, 이미 같은 모드(-4046)면 정상 처리")
    void switchToIsolated() {
        givenKey();
        willThrow(new BinanceApiException(-4046, "No need to change margin type."))
                .given(feignClient).changeMarginType("BTCUSDT", "ISOLATED");

        client.switchToIsolated(1L, "BTCUSDT");

        then(feignClient).should().changeMarginType("BTCUSDT", "ISOLATED");
    }

    @Test
    @DisplayName("switchToIsolated: 그 외 오류는 CustomException(SWITCH_MARGIN_MODE_FAILED)")
    void switchToIsolated_failure() {
        givenKey();
        willThrow(new BinanceApiException(-4047, "Margin type cannot be changed if there exists open orders."))
                .given(feignClient).changeMarginType("BTCUSDT", "ISOLATED");

        assertThatThrownBy(() -> client.switchToIsolated(1L, "BTCUSDT"))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getExceptionMessage())
                        .isEqualTo(ExceptionMessage.SWITCH_MARGIN_MODE_FAILED));
    }

    @Test
    @DisplayName("setLeverage: 심볼 레버리지를 설정하고, 오류는 CustomException(SET_LEVERAGE_FAILED)")
    void setLeverage() {
        givenKey();
        client.setLeverage(1L, "BTCUSDT", "10");
        then(feignClient).should().changeLeverage("BTCUSDT", "10");

        willThrow(new BinanceApiException(-4028, "Leverage 200 is not valid"))
                .given(feignClient).changeLeverage("ETHUSDT", "200");
        assertThatThrownBy(() -> client.setLeverage(1L, "ETHUSDT", "200"))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getExceptionMessage())
                        .isEqualTo(ExceptionMessage.SET_LEVERAGE_FAILED));
    }

    // ─────────────────────────────────────────────
    // 체결 조회 / 수수료 환산
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("getExecution: 주문의 체결 내역을 가중 평균가·수수료 합·수량 합으로 집계하고 원 주문 수량을 채운다")
    void getExecution_aggregates() throws Exception {
        givenKey();
        given(feignClient.getUserTrades(eq("BTCUSDT"), eq("22542179"), isNull(), anyInt()))
                .willReturn(fixtureList("getUserTrades_entry", BinanceUserTradeResponse.class));
        given(feignClient.getOrder("BTCUSDT", "22542179", null)).willReturn(fixture("getOrder_filled", BinanceOrderResponse.class));

        ExecutionDTO exec = client.getExecution("22542179", CLIENT_ORDER_ID, "BTCUSDT", 1L);

        assertThat(exec.getOrderId()).isEqualTo("22542179");
        assertThat(exec.getSide()).isEqualTo(OrderSide.BUY);
        assertThat(exec.getExecPrice()).isEqualTo("50006");
        assertThat(exec.getExecQty()).isEqualTo("0.01");
        assertThat(exec.getExecFee()).isEqualTo("0.25003");
        assertThat(exec.getOrderQty()).isEqualTo("0.010");
        assertThat(exec.getExecTime()).isEqualTo("1761526800100");
    }

    @Test
    @DisplayName("getExecution: BNB 수수료는 BNBUSDT 시세로 USDT 환산한다")
    void getExecution_bnbFeeConverted() {
        givenKey();
        given(feignClient.getUserTrades(eq("BTCUSDT"), eq("22542179"), isNull(), anyInt()))
                .willReturn(List.of(trade("22542179", "BUY", "50000", "0.010", "0.0004", "BNB", "0", 1761526800100L)));
        given(marketClient.getTickers()).willReturn(List.of(ticker("BTCUSDT", "50000"), ticker("BNBUSDT", "600")));

        ExecutionDTO exec = client.getExecution("22542179", CLIENT_ORDER_ID, "BTCUSDT", 1L);

        // 0.0004 BNB × 600 = 0.24 USDT
        assertThat(exec.getExecFee()).isEqualTo("0.24");
    }

    @Test
    @DisplayName("getExecution: BNB 시세를 모르면 체결가 × 수량 × taker 요율(0.05%)로 추정한다")
    void getExecution_bnbFeeFallback() {
        givenKey();
        given(feignClient.getUserTrades(eq("BTCUSDT"), eq("22542179"), isNull(), anyInt()))
                .willReturn(List.of(trade("22542179", "BUY", "50000", "0.010", "0.0004", "BNB", "0", 1761526800100L)));
        given(marketClient.getTickers()).willReturn(List.of());

        ExecutionDTO exec = client.getExecution("22542179", CLIENT_ORDER_ID, "BTCUSDT", 1L);

        // 50000 × 0.010 × 0.0005 = 0.25 USDT
        assertThat(exec.getExecFee()).isEqualTo("0.25");
    }

    @Test
    @DisplayName("getExecution: 원 주문 조회가 실패해도 체결 집계는 반환한다 (주문 수량 없음 → 반영 완료 간주)")
    void getExecution_orderLookupFails() throws Exception {
        givenKey();
        given(feignClient.getUserTrades(eq("BTCUSDT"), eq("22542179"), isNull(), anyInt()))
                .willReturn(fixtureList("getUserTrades_entry", BinanceUserTradeResponse.class));
        given(feignClient.getOrder("BTCUSDT", "22542179", null)).willThrow(new BinanceApiException(-1001, "Internal error"));

        ExecutionDTO exec = client.getExecution("22542179", CLIENT_ORDER_ID, "BTCUSDT", 1L);

        assertThat(exec.getExecQty()).isEqualTo("0.01");
        assertThat(exec.getOrderQty()).isNull();
    }

    @Test
    @DisplayName("expectExecution: 3-4 단계는 스트림이 없어 대기 등록하지 않는다 (REST 조회로 진행)")
    void expectExecution_noStream() {
        assertThat(client.expectExecution(1L, CLIENT_ORDER_ID)).isFalse();
    }

    // ─────────────────────────────────────────────
    // 청산 손익
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("getClosedPnl: 실현손익 − 진입 수수료 − 청산 수수료 + 진입 이후 펀딩비 수입, 펀딩비는 지급 양수로 반환")
    void getClosedPnl() throws Exception {
        givenKey();
        given(clock.millis()).willReturn(NOW);
        given(feignClient.getUserTrades(eq("BTCUSDT"), eq("22542200"), isNull(), anyInt()))
                .willReturn(fixtureList("getUserTrades_close", BinanceUserTradeResponse.class));
        given(feignClient.getIncome(eq("BTCUSDT"), eq("FUNDING_FEE"), eq(1761526800000L), eq(NOW), anyInt()))
                .willReturn(fixtureList("getIncome", BinanceIncomeResponse.class));

        ClosedPnlDTO pnl = client.getClosedPnl(closedPnlQuery(), 1L);

        // 8 − 0.5 − 0.25 + (−0.5 + 0.2) = 6.95
        assertThat(pnl.getOrderId()).isEqualTo("22542200");
        assertThat(new BigDecimal(pnl.getClosedPnl())).isEqualByComparingTo("6.95");
        assertThat(new BigDecimal(pnl.getFundingFee())).isEqualByComparingTo("0.3");
    }

    @Test
    @DisplayName("getClosedPnl: 청산 체결이 아직 반영되지 않으면 재조회 후 null (계산식 fallback)")
    void getClosedPnl_notReflected() {
        givenKey();
        given(feignClient.getUserTrades(eq("BTCUSDT"), eq("22542200"), isNull(), anyInt())).willReturn(List.of());

        assertThat(client.getClosedPnl(closedPnlQuery(), 1L)).isNull();
        then(feignClient).should(times(2)).getUserTrades(eq("BTCUSDT"), eq("22542200"), isNull(), anyInt());
        then(feignClient).should(never()).getIncome(anyString(), anyString(), anyLong(), anyLong(), anyInt());
    }

    // ─────────────────────────────────────────────
    // findLastClose / getPositionSize
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("findLastClose: 기준 시각 이후 실현손익이 있는 최근 체결의 주문을 찾아 주문 유형으로 청산 방식을 판정한다")
    void findLastClose() throws Exception {
        givenKey();
        given(clock.millis()).willReturn(NOW);
        given(feignClient.getUserTrades(eq("BTCUSDT"), isNull(), eq(1761526800000L), anyInt()))
                .willReturn(fixtureList("getUserTrades_recent", BinanceUserTradeResponse.class));
        given(feignClient.getOrder("BTCUSDT", "22542200", null)).willReturn(fixture("getOrder_takeProfit", BinanceOrderResponse.class));

        PositionCloseDTO close = client.findLastClose(1L, "BTCUSDT", 1761526800000L);

        assertThat(close.getUserId()).isEqualTo(1L);
        assertThat(close.getExchangeType()).isEqualTo(ExchangeType.BINANCE);
        assertThat(close.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(close.getOrderId()).isEqualTo("22542200");
        assertThat(close.getOrderLinkId()).isEqualTo("web_tpTriggered01");
        // (50800 × 0.006 + 50810 × 0.004) / 0.010
        assertThat(close.getExecPrice()).isEqualTo("50804");
        assertThat(close.getCloseType()).isEqualTo(PositionCloseType.TAKE_PROFIT);
    }

    @Test
    @DisplayName("findLastClose: 청산 체결이 없으면 orderId null, OTHER")
    void findLastClose_none() throws Exception {
        givenKey();
        given(clock.millis()).willReturn(NOW);
        given(feignClient.getUserTrades(eq("BTCUSDT"), isNull(), eq(1761526800000L), anyInt()))
                .willReturn(fixtureList("getUserTrades_entry", BinanceUserTradeResponse.class));

        PositionCloseDTO close = client.findLastClose(1L, "BTCUSDT", 1761526800000L);

        assertThat(close.getOrderId()).isNull();
        assertThat(close.getCloseType()).isEqualTo(PositionCloseType.OTHER);
    }

    @Test
    @DisplayName("findLastClose: 7일보다 이전 기준 시각은 7일 전으로 보정한다")
    void findLastClose_clampsRange() {
        givenKey();
        given(clock.millis()).willReturn(NOW);
        long sevenDaysAgo = NOW - 7L * 24 * 60 * 60 * 1000;
        given(feignClient.getUserTrades(eq("BTCUSDT"), isNull(), eq(sevenDaysAgo), anyInt())).willReturn(List.of());

        PositionCloseDTO close = client.findLastClose(1L, "BTCUSDT", 0L);

        assertThat(close.getCloseType()).isEqualTo(PositionCloseType.OTHER);
    }

    @Test
    @DisplayName("findLastClose: 체결 조회 실패면 null (다음 재조정 주기에 재시도)")
    void findLastClose_failure() {
        givenKey();
        given(clock.millis()).willReturn(NOW);
        given(feignClient.getUserTrades(anyString(), any(), any(), anyInt()))
                .willThrow(new BinanceApiException(-1003, "Too many requests"));

        assertThat(client.findLastClose(1L, "BTCUSDT", 1761526800000L)).isNull();
    }

    @Test
    @DisplayName("getPositionSize: positionAmt 절댓값 합계, 조회 실패면 null")
    void getPositionSize() throws Exception {
        givenKey();
        given(feignClient.getPositionRisk("BTCUSDT"))
                .willReturn(fixtureList("getPositionRisk", BinancePositionRiskResponse.class));
        assertThat(client.getPositionSize(1L, "BTCUSDT")).isEqualByComparingTo("0.010");

        given(feignClient.getPositionRisk("ETHUSDT")).willThrow(new BinanceApiException(-1001, "Internal error"));
        assertThat(client.getPositionSize(1L, "ETHUSDT")).isNull();
    }

    @Test
    @DisplayName("getPositionSize: 포지션이 없으면(빈 목록) 0")
    void getPositionSize_empty() {
        givenKey();
        given(feignClient.getPositionRisk("BTCUSDT")).willReturn(List.of());

        assertThat(client.getPositionSize(1L, "BTCUSDT")).isEqualByComparingTo("0");
    }

    // ─────────────────────────────────────────────
    // 헬퍼 메서드
    // ─────────────────────────────────────────────

    private OrderRequestDTO entry(String takeProfit, String stopLoss) {
        return OrderRequestDTO.builder()
                .symbol("BTCUSDT").side(OrderSide.BUY).qty("0.010").reduceOnly(false)
                .clientOrderId(CLIENT_ORDER_ID).takeProfit(takeProfit).stopLoss(stopLoss).build();
    }

    private ClosedPnlQueryDTO closedPnlQuery() {
        return ClosedPnlQueryDTO.builder()
                .exchangeType(ExchangeType.BINANCE).orderId("22542200").symbol("BTCUSDT")
                .entryTime(1761526800000L).entryFee(new BigDecimal("0.5")).build();
    }

    private BinanceUserTradeResponse trade(String orderId, String side, String price, String qty,
                                           String commission, String commissionAsset, String realizedPnl, long time) {
        BinanceUserTradeResponse t = new BinanceUserTradeResponse();
        t.setSymbol("BTCUSDT");
        t.setOrderId(orderId);
        t.setSide(side);
        t.setPrice(price);
        t.setQty(qty);
        t.setCommission(commission);
        t.setCommissionAsset(commissionAsset);
        t.setRealizedPnl(realizedPnl);
        t.setTime(time);
        return t;
    }

    private TickerDTO ticker(String symbol, String lastPrice) {
        return TickerDTO.builder().symbol(symbol).lastPrice(lastPrice).build();
    }

    private <T> T fixture(String name, Class<T> type) throws Exception {
        return objectMapper.readValue(new File(FILES + "BinanceTradeClient_" + name + ".json"), type);
    }

    private <T> List<T> fixtureList(String name, Class<T> type) throws Exception {
        return objectMapper.readValue(new File(FILES + "BinanceTradeClient_" + name + ".json"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, type));
    }

    private void givenKey() {
        ExchangeKeyDTO key = ExchangeKeyDTO.builder()
                .userId(1L).exchangeType(ExchangeType.BINANCE)
                .apiKey("encKey").apiSecret("encSecret").build();
        given(exchangeKeyService.findKey(1L, ExchangeType.BINANCE)).willReturn(key);
        given(aesEncryptor.decrypt("encKey")).willReturn("rawKey");
        given(aesEncryptor.decrypt("encSecret")).willReturn("rawSecret");
    }

    private RetryableException makeTimeoutException() {
        Request request = Request.create(Request.HttpMethod.POST, "/fapi/v1/order",
                Collections.emptyMap(), null, StandardCharsets.UTF_8, null);
        return new RetryableException(-1, "Read timed out", Request.HttpMethod.POST, (Long) null, request);
    }
}
