package com.coin.webcointrader.bybit.trade;

import com.coin.webcointrader.bybit.trade.PositionClient;
import com.coin.webcointrader.bybit.stream.BybitExecutionWaiter;
import com.coin.webcointrader.bybit.stream.BybitPrivateWebSocketClient;
import com.coin.webcointrader.bybit.stream.dto.WebSocketExecutionDTO;
import com.coin.webcointrader.bybit.trade.dto.CancelOrderRequest;
import com.coin.webcointrader.bybit.trade.dto.CancelOrderResponse;
import com.coin.webcointrader.bybit.trade.dto.CreateOrderRequest;
import com.coin.webcointrader.bybit.trade.dto.SetLeverageRequest;
import com.coin.webcointrader.bybit.trade.dto.SetMarginModeRequest;
import com.coin.webcointrader.bybit.trade.dto.CreateOrderResponse;
import com.coin.webcointrader.bybit.trade.dto.GetClosedPnlResponse;
import com.coin.webcointrader.bybit.trade.dto.GetExecutionListResponse;
import com.coin.webcointrader.bybit.trade.dto.GetOpenOrdersResponse;
import com.coin.webcointrader.bybit.trade.dto.GetPositionListResponse;
import com.coin.webcointrader.bybit.trade.dto.SetLeverageResponse;
import com.coin.webcointrader.bybit.trade.dto.SetMarginModeResponse;
import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.enums.OrderCancelResult;
import com.coin.webcointrader.common.enums.OrderSide;
import com.coin.webcointrader.common.enums.PositionCloseType;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.trade.dto.PositionCloseDTO;
import com.coin.webcointrader.common.trade.dto.ClosedPnlDTO;
import com.coin.webcointrader.common.trade.dto.ClosedPnlQueryDTO;
import com.coin.webcointrader.common.trade.dto.ExecutionDTO;
import com.coin.webcointrader.common.trade.dto.OrderRequestDTO;
import com.coin.webcointrader.common.trade.dto.OrderResultDTO;
import com.coin.webcointrader.common.util.AesEncryptor;
import com.coin.webcointrader.common.util.UserApiKeyContext;
import com.coin.webcointrader.common.login.dto.ExchangeKeyDTO;
import com.coin.webcointrader.common.login.service.ExchangeKeyService;
import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.RetryableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * Bybit 거래 클라이언트 단위 테스트.
 * 공통 처리(키 바인딩·설정 캐시·복구 재시도·체결 집계·펀딩비 역산)는 AbstractExchangeTradeClientTest에서 검증하고,
 * 여기서는 Bybit 요청 변환·응답 코드·DTO 변환·private 스트림을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class BybitTradeClientTest {

    @Mock
    private TradeClient tradeClient;

    @Mock
    private PositionClient positionClient;

    @Mock
    private BybitExecutionWaiter bybitExecutionWaiter;

    @Mock
    private BybitPrivateWebSocketClient bybitPrivateWebSocketClient;

    @Mock
    private ExchangeKeyService exchangeKeyService;

    @Mock
    private AesEncryptor aesEncryptor;

    @Mock
    private Clock clock;

    private BybitTradeClient client;

    @BeforeEach
    void setUp() {
        // 재시도 대기 없이 실행
        client = new BybitTradeClient(tradeClient, positionClient, bybitExecutionWaiter, bybitPrivateWebSocketClient,
                exchangeKeyService, aesEncryptor, clock) {
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
    // placeOrder: 요청 변환·응답 코드
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("placeOrder: 공통 주문을 Bybit 시장가 주문(linear, Buy)으로 변환하고 주문 ID를 반환한다")
    void placeOrder_mapsToBybitMarketOrder() {
        stubApiKey(1L);
        given(tradeClient.createOrder(any())).willReturn(ResponseEntity.ok(makeCreateOrderResponse("0", "order-1")));

        OrderResultDTO result = client.placeOrder(OrderRequestDTO.builder()
                .symbol("BTCUSDT").side(OrderSide.BUY).qty("0.01").clientOrderId("q1-E-1").build(), 1L);

        CreateOrderRequest sent = capturedOrder();
        assertThat(sent.getCategory()).isEqualTo("linear");
        assertThat(sent.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(sent.getSide()).isEqualTo("Buy");
        assertThat(sent.getOrderType()).isEqualTo("Market");
        assertThat(sent.getQty()).isEqualTo("0.01");
        assertThat(sent.getOrderLinkId()).isEqualTo("q1-E-1");
        assertThat(sent.getPositionIdx()).isEqualTo(0); // 단방향 모드 명시
        assertThat(sent.getReduceOnly()).isNull();
        assertThat(sent.getTpslMode()).isNull();
        assertThat(result.getOrderId()).isEqualTo("order-1");
        assertThat(result.getClientOrderId()).isEqualTo("q1-E-1");
    }

    @Test
    @DisplayName("placeOrder: 청산 주문(SELL, reduceOnly)을 Sell·reduceOnly true로 변환한다")
    void placeOrder_mapsSellReduceOnly() {
        stubApiKey(1L);
        given(tradeClient.createOrder(any())).willReturn(ResponseEntity.ok(makeCreateOrderResponse("0", "order-1")));

        client.placeOrder(OrderRequestDTO.builder()
                .symbol("BTCUSDT").side(OrderSide.SELL).qty("0.01").reduceOnly(true).clientOrderId("q1-S-1").build(), 1L);

        CreateOrderRequest sent = capturedOrder();
        assertThat(sent.getSide()).isEqualTo("Sell");
        assertThat(sent.getReduceOnly()).isTrue();
    }

    @Test
    @DisplayName("placeOrder: price가 있으면 지정가(Limit)·가격·GTC로 변환한다 (익절 지정가)")
    void placeOrder_mapsLimitOrder() {
        stubApiKey(1L);
        given(tradeClient.createOrder(any())).willReturn(ResponseEntity.ok(makeCreateOrderResponse("0", "order-1")));

        client.placeOrder(OrderRequestDTO.builder()
                .symbol("BTCUSDT").side(OrderSide.SELL).qty("0.01").reduceOnly(true)
                .price("52000.5").clientOrderId("q1-T-1").build(), 1L);

        CreateOrderRequest sent = capturedOrder();
        assertThat(sent.getOrderType()).isEqualTo("Limit");
        assertThat(sent.getPrice()).isEqualTo("52000.5");
        assertThat(sent.getTimeInForce()).isEqualTo("GTC");
        assertThat(sent.getReduceOnly()).isTrue();
        assertThat(sent.getOrderLinkId()).isEqualTo("q1-T-1");
    }

    // ─────────────────────────────────────────────
    // cancelOrder: 주문 취소 (POST /v5/order/cancel)
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("cancelOrder: linear·심볼·orderLinkId로 취소하고 retCode 0이면 CANCELLED")
    void cancelOrder_success() {
        stubApiKey(1L);
        given(tradeClient.cancelOrder(any())).willReturn(ResponseEntity.ok(makeCancelOrderResponse("0")));

        OrderCancelResult result = client.cancelOrder(1L, "BTCUSDT", "q1-T-1");

        ArgumentCaptor<CancelOrderRequest> captor = ArgumentCaptor.forClass(CancelOrderRequest.class);
        then(tradeClient).should().cancelOrder(captor.capture());
        assertThat(captor.getValue().getCategory()).isEqualTo("linear");
        assertThat(captor.getValue().getSymbol()).isEqualTo("BTCUSDT");
        assertThat(captor.getValue().getOrderLinkId()).isEqualTo("q1-T-1");
        assertThat(result).isEqualTo(OrderCancelResult.CANCELLED);
    }

    @Test
    @DisplayName("cancelOrder: retCode 110001(주문 없음·이미 체결)이면 NOT_FOUND")
    void cancelOrder_notFound() {
        stubApiKey(1L);
        given(tradeClient.cancelOrder(any())).willReturn(ResponseEntity.ok(makeCancelOrderResponse("110001")));

        assertThat(client.cancelOrder(1L, "BTCUSDT", "q1-T-1")).isEqualTo(OrderCancelResult.NOT_FOUND);
    }

    @Test
    @DisplayName("cancelOrder: 그 외 retCode면 FAILED")
    void cancelOrder_otherRetCode_failed() {
        stubApiKey(1L);
        given(tradeClient.cancelOrder(any())).willReturn(ResponseEntity.ok(makeCancelOrderResponse("10006")));

        assertThat(client.cancelOrder(1L, "BTCUSDT", "q1-T-1")).isEqualTo(OrderCancelResult.FAILED);
    }

    @Test
    @DisplayName("cancelOrder: 타임아웃 등 예외는 FAILED (예외 전파 안 함)")
    void cancelOrder_exception_failed() {
        stubApiKey(1L);
        given(tradeClient.cancelOrder(any())).willThrow(makeTimeoutException());

        assertThat(client.cancelOrder(1L, "BTCUSDT", "q1-T-1")).isEqualTo(OrderCancelResult.FAILED);
    }

    @Test
    @DisplayName("placeOrder: price가 없으면 시장가로 보내고 가격·timeInForce는 생략한다")
    void placeOrder_marketOmitsPrice() {
        stubApiKey(1L);
        given(tradeClient.createOrder(any())).willReturn(ResponseEntity.ok(makeCreateOrderResponse("0", "order-1")));

        client.placeOrder(order("q1-E-1"), 1L);

        CreateOrderRequest sent = capturedOrder();
        assertThat(sent.getOrderType()).isEqualTo("Market");
        assertThat(sent.getPrice()).isNull();
        assertThat(sent.getTimeInForce()).isNull();
    }

    @Test
    @DisplayName("placeOrder: TP/SL이 있으면 시장가·최종 체결가 트리거·Full 모드로 동봉한다")
    void placeOrder_mapsTpSl() {
        stubApiKey(1L);
        given(tradeClient.createOrder(any())).willReturn(ResponseEntity.ok(makeCreateOrderResponse("0", "order-1")));

        client.placeOrder(OrderRequestDTO.builder().symbol("BTCUSDT").side(OrderSide.BUY).qty("0.01")
                .takeProfit("55000").stopLoss("45000").build(), 1L);

        CreateOrderRequest sent = capturedOrder();
        assertThat(sent.getTakeProfit()).isEqualTo("55000");
        assertThat(sent.getTpOrderType()).isEqualTo("Market");
        assertThat(sent.getTpTriggerBy()).isEqualTo("LastPrice");
        assertThat(sent.getStopLoss()).isEqualTo("45000");
        assertThat(sent.getSlOrderType()).isEqualTo("Market");
        assertThat(sent.getSlTriggerBy()).isEqualTo("LastPrice");
        assertThat(sent.getTpslMode()).isEqualTo("Full");
    }

    @Test
    @DisplayName("placeOrder: TP만 있으면 SL 필드는 비우고 Full 모드로 동봉한다")
    void placeOrder_mapsTpOnly() {
        stubApiKey(1L);
        given(tradeClient.createOrder(any())).willReturn(ResponseEntity.ok(makeCreateOrderResponse("0", "order-1")));

        client.placeOrder(OrderRequestDTO.builder().symbol("BTCUSDT").side(OrderSide.BUY).qty("0.01")
                .takeProfit("55000").build(), 1L);

        CreateOrderRequest sent = capturedOrder();
        assertThat(sent.getTakeProfit()).isEqualTo("55000");
        assertThat(sent.getStopLoss()).isNull();
        assertThat(sent.getSlOrderType()).isNull();
        assertThat(sent.getTpslMode()).isEqualTo("Full");
    }

    @Test
    @DisplayName("placeOrder: retCode가 0이 아니면 거래소 코드·메시지로 CustomException(ORDER_FAILED) 발생, 키 컨텍스트는 정리된다")
    void placeOrder_errorRetCode_throwsOrderFailed() {
        stubApiKey(1L);
        CreateOrderResponse error = makeCreateOrderResponse("110007", null);
        error.setRetMsg("Insufficient margin");
        given(tradeClient.createOrder(any())).willReturn(ResponseEntity.ok(error));

        assertThatThrownBy(() -> client.placeOrder(order("q1-E-1"), 1L))
                .isInstanceOf(CustomException.class)
                .hasMessage(ExceptionMessage.ORDER_FAILED.getMessage() + ": [110007] Insufficient margin")
                .satisfies(e -> assertThat(((CustomException) e).getExceptionMessage()).isEqualTo(ExceptionMessage.ORDER_FAILED));
        assertThat(UserApiKeyContext.getApiKey()).isNull();
        assertThat(UserApiKeyContext.getApiSecret()).isNull();
    }

    @Test
    @DisplayName("placeOrder: 응답 본문이 없으면 CustomException(ORDER_FAILED, 응답 없음) 발생")
    void placeOrder_emptyBody_throwsOrderFailed() {
        stubApiKey(1L);
        given(tradeClient.createOrder(any())).willReturn(ResponseEntity.ok(null));

        assertThatThrownBy(() -> client.placeOrder(order("q1-E-1"), 1L))
                .isInstanceOf(CustomException.class)
                .hasMessage(ExceptionMessage.ORDER_FAILED.getMessage() + ": 응답 없음");
    }

    @Test
    @DisplayName("placeOrder: Bybit 키가 없으면 CustomException(API_KEY_NOT_FOUND) 발생")
    void placeOrder_apiKeyNotFound() {
        given(exchangeKeyService.findKey(99L, ExchangeType.BYBIT))
                .willThrow(new CustomException(ExceptionMessage.API_KEY_NOT_FOUND));

        assertThatThrownBy(() -> client.placeOrder(order("q1-E-1"), 99L))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining(ExceptionMessage.API_KEY_NOT_FOUND.getMessage());
        then(tradeClient).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("placeOrder: 주문 중 Bybit 키를 UserApiKeyContext에 바인딩한다")
    void placeOrder_bindsApiKeyContext() {
        stubApiKey(1L);
        List<String> boundKeys = new ArrayList<>();
        given(tradeClient.createOrder(any())).willAnswer(inv -> {
            boundKeys.add(UserApiKeyContext.getApiKey() + "/" + UserApiKeyContext.getApiSecret());
            return ResponseEntity.ok(makeCreateOrderResponse("0", "order-1"));
        });

        client.placeOrder(order("q1-E-1"), 1L);

        assertThat(boundKeys).containsExactly("raw/raw");
        assertThat(UserApiKeyContext.getApiKey()).isNull();
    }

    // ─────────────────────────────────────────────
    // placeOrder: 결과 불명확(타임아웃·5xx) 복구
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("placeOrder: 타임아웃이어도 orderLinkId 조회 결과 체결됐으면 조회된 주문 ID를 반환한다")
    void placeOrder_timeout_filled_recovers() {
        stubApiKey(1L);
        given(tradeClient.createOrder(any())).willThrow(makeTimeoutException());
        given(tradeClient.getOpenOrders("linear", "BTCUSDT", "q1-E-1"))
                .willReturn(ResponseEntity.ok(makeOpenOrdersResponse("Filled", "0.01")));

        OrderResultDTO result = client.placeOrder(order("q1-E-1"), 1L);

        assertThat(result.getOrderId()).isEqualTo("order-999");
        assertThat(result.getClientOrderId()).isEqualTo("q1-E-1");
    }

    @Test
    @DisplayName("placeOrder: 5xx 응답이어도 접수(New) 확인되면 주문 ID를 반환한다")
    void placeOrder_5xx_new_recovers() {
        stubApiKey(1L);
        given(tradeClient.createOrder(any())).willThrow(makeHttpException(502));
        given(tradeClient.getOpenOrders(anyString(), anyString(), anyString()))
                .willReturn(ResponseEntity.ok(makeOpenOrdersResponse("New", "0")));

        assertThat(client.placeOrder(order("q1-E-1"), 1L).getOrderId()).isEqualTo("order-999");
    }

    @Test
    @DisplayName("placeOrder: 타임아웃 후 조회 결과 Rejected(체결 0)면 원래 예외를 던진다")
    void placeOrder_timeout_rejected_throwsOriginal() {
        stubApiKey(1L);
        given(tradeClient.createOrder(any())).willThrow(makeTimeoutException());
        given(tradeClient.getOpenOrders(anyString(), anyString(), anyString()))
                .willReturn(ResponseEntity.ok(makeOpenOrdersResponse("Rejected", "0")));

        assertThatThrownBy(() -> client.placeOrder(order("q1-E-1"), 1L)).isInstanceOf(RetryableException.class);
    }

    @Test
    @DisplayName("placeOrder: 체결 수량이 있으면 취소 상태여도 접수로 본다 (PartiallyFilledCanceled)")
    void placeOrder_timeout_partiallyFilledCanceled_recovers() {
        stubApiKey(1L);
        given(tradeClient.createOrder(any())).willThrow(makeTimeoutException());
        given(tradeClient.getOpenOrders(anyString(), anyString(), anyString()))
                .willReturn(ResponseEntity.ok(makeOpenOrdersResponse("PartiallyFilledCanceled", "0.005")));

        assertThat(client.placeOrder(order("q1-E-1"), 1L).getOrderId()).isEqualTo("order-999");
    }

    @Test
    @DisplayName("placeOrder: 타임아웃 후 조회해도 주문이 없거나 에러 응답이면 2회 조회 후 원래 예외를 던진다")
    void placeOrder_timeout_notFound_throwsOriginal() {
        stubApiKey(1L);
        given(tradeClient.createOrder(any())).willThrow(makeTimeoutException());
        GetOpenOrdersResponse error = makeEmptyOpenOrdersResponse();
        error.setRetCode("10001");
        given(tradeClient.getOpenOrders(anyString(), anyString(), anyString()))
                .willReturn(ResponseEntity.ok(makeEmptyOpenOrdersResponse()))
                .willReturn(ResponseEntity.ok(error));

        assertThatThrownBy(() -> client.placeOrder(order("q1-E-1"), 1L)).isInstanceOf(RetryableException.class);
        then(tradeClient).should(times(2)).getOpenOrders(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("placeOrder: 4xx 응답은 거부 확정이므로 조회 없이 예외를 던진다")
    void placeOrder_4xx_noLookup() {
        stubApiKey(1L);
        given(tradeClient.createOrder(any())).willThrow(makeHttpException(400));

        assertThatThrownBy(() -> client.placeOrder(order("q1-E-1"), 1L)).isInstanceOf(FeignException.class);
        then(tradeClient).should(never()).getOpenOrders(any(), any(), any());
    }

    @Test
    @DisplayName("placeOrder: Feign 외 예외는 결과 불명확으로 보지 않아 조회 없이 던진다")
    void placeOrder_nonFeignException_noLookup() {
        stubApiKey(1L);
        given(tradeClient.createOrder(any())).willThrow(new RuntimeException("API 오류"));

        assertThatThrownBy(() -> client.placeOrder(order("q1-E-1"), 1L)).isInstanceOf(RuntimeException.class);
        then(tradeClient).should(never()).getOpenOrders(any(), any(), any());
    }

    // ─────────────────────────────────────────────
    // switchToIsolated / setLeverage
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("switchToIsolated: ISOLATED_MARGIN으로 요청하고, 110026(이미 설정됨)은 성공으로 본다")
    void switchToIsolated_requestsIsolated_alreadySetIsOk() {
        stubApiKey(1L);
        given(positionClient.setMarginMode(any())).willReturn(ResponseEntity.ok(makeMarginModeResponse("110026")));

        client.switchToIsolated(1L, "BTCUSDT");

        ArgumentCaptor<SetMarginModeRequest> captor = ArgumentCaptor.forClass(SetMarginModeRequest.class);
        then(positionClient).should().setMarginMode(captor.capture());
        assertThat(captor.getValue().getSetMarginMode()).isEqualTo("ISOLATED_MARGIN");
    }

    @Test
    @DisplayName("switchToIsolated: 그 외 에러 코드면 거래소 코드·메시지로 CustomException(SWITCH_MARGIN_MODE_FAILED) 발생")
    void switchToIsolated_error_throws() {
        stubApiKey(1L);
        given(positionClient.setMarginMode(any())).willReturn(ResponseEntity.ok(makeMarginModeResponse("3400045")));

        assertThatThrownBy(() -> client.switchToIsolated(1L, "BTCUSDT"))
                .isInstanceOf(CustomException.class)
                .hasMessage(ExceptionMessage.SWITCH_MARGIN_MODE_FAILED.getMessage() + ": [3400045] error");
    }

    @Test
    @DisplayName("setLeverage: 롱·숏 같은 값으로 linear 레버리지를 요청하고, 110043(동일 값)은 성공으로 본다")
    void setLeverage_requestsBothSides_sameValueIsOk() {
        stubApiKey(1L);
        given(positionClient.setLeverage(any())).willReturn(ResponseEntity.ok(makeLeverageResponse("110043")));

        client.setLeverage(1L, "BTCUSDT", "10");

        ArgumentCaptor<SetLeverageRequest> captor = ArgumentCaptor.forClass(SetLeverageRequest.class);
        then(positionClient).should().setLeverage(captor.capture());
        assertThat(captor.getValue().getCategory()).isEqualTo("linear");
        assertThat(captor.getValue().getSymbol()).isEqualTo("BTCUSDT");
        assertThat(captor.getValue().getBuyLeverage()).isEqualTo("10");
        assertThat(captor.getValue().getSellLeverage()).isEqualTo("10");
    }

    @Test
    @DisplayName("setLeverage: 그 외 에러 코드면 거래소 코드·메시지로 CustomException(SET_LEVERAGE_FAILED) 발생")
    void setLeverage_error_throws() {
        stubApiKey(1L);
        given(positionClient.setLeverage(any())).willReturn(ResponseEntity.ok(makeLeverageResponse("10001")));

        assertThatThrownBy(() -> client.setLeverage(1L, "BTCUSDT", "10"))
                .isInstanceOf(CustomException.class)
                .hasMessage(ExceptionMessage.SET_LEVERAGE_FAILED.getMessage() + ": [10001] error");
    }

    // ─────────────────────────────────────────────
    // 체결 조회
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("expectExecution / cancelExecution: Bybit 체결 대기 등록·취소에 위임한다")
    void expectAndCancelExecution_delegateToWaiter() {
        given(bybitExecutionWaiter.expect(1L, "q1-E-1")).willReturn(true);

        assertThat(client.expectExecution(1L, "q1-E-1")).isTrue();
        client.cancelExecution("q1-E-1");

        then(bybitExecutionWaiter).should().cancel("q1-E-1");
    }

    @Test
    @DisplayName("getExecution: WS 체결을 받으면 공통 DTO로 변환해 반환하고 REST 조회는 하지 않는다")
    void getExecution_ws_convertsWithoutRest() {
        GetExecutionListResponse.ExecutionInfo ws = makeExec("50300.5", "0.002", "0.0553", "0.002");
        ws.setSide("Sell");
        ws.setExecTime("1700000000000");
        given(bybitExecutionWaiter.await("q1-E-1", 2000L)).willReturn(ws);

        ExecutionDTO result = client.getExecution("order-123", "q1-E-1", "BTCUSDT", 1L);

        assertThat(result.getExecPrice()).isEqualTo("50300.5");
        assertThat(result.getExecFee()).isEqualTo("0.0553");
        assertThat(result.getExecQty()).isEqualTo("0.002");
        assertThat(result.getOrderQty()).isEqualTo("0.002");
        assertThat(result.getSide()).isEqualTo(OrderSide.SELL);
        assertThat(result.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(result.getOrderId()).isEqualTo("order-123");
        assertThat(result.getExecTime()).isEqualTo("1700000000000");
        then(tradeClient).should(never()).getExecutionList(any(), any(), any(), anyInt(), any());
        then(exchangeKeyService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("getExecution: WS 체결이 없으면 linear 체결 내역을 nextPageCursor 따라 전부 모아 집계한다")
    void getExecution_rest_followsCursorAndAggregates() {
        stubApiKey(1L);
        given(bybitExecutionWaiter.await("q1-E-1", 2000L)).willReturn(null);
        given(tradeClient.getExecutionList(eq("linear"), eq("BTCUSDT"), eq("order-123"), eq(100), isNull()))
                .willReturn(ResponseEntity.ok(makeExecutionPage("page2", makeExec("50000", "0.001", "0.0275", "0.004"))));
        given(tradeClient.getExecutionList(eq("linear"), eq("BTCUSDT"), eq("order-123"), eq(100), eq("page2")))
                .willReturn(ResponseEntity.ok(makeExecutionPage(null, makeExec("50100", "0.003", "0.0827", "0.004"))));

        ExecutionDTO result = client.getExecution("order-123", "q1-E-1", "BTCUSDT", 1L);

        // (50000×0.001 + 50100×0.003) / 0.004 = 50075
        assertThat(new BigDecimal(result.getExecPrice())).isEqualByComparingTo("50075");
        assertThat(new BigDecimal(result.getExecFee())).isEqualByComparingTo("0.1102");
        assertThat(new BigDecimal(result.getExecQty())).isEqualByComparingTo("0.004");
    }

    @Test
    @DisplayName("getExecution: 체결 내역 에러 응답이 계속되면 null을 반환한다")
    void getExecution_rest_errorReturnsNull() {
        stubApiKey(1L);
        GetExecutionListResponse error = makeEmptyExecutionListResponse();
        error.setRetCode("10001");
        given(tradeClient.getExecutionList(anyString(), anyString(), anyString(), anyInt(), any()))
                .willReturn(ResponseEntity.ok(error));

        assertThat(client.getExecution("order-123", null, "BTCUSDT", 1L)).isNull();
    }

    // ─────────────────────────────────────────────
    // getClosedPnl
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("getClosedPnl: linear 청산 손익 50건을 조회해 주문 ID 일치 건을 공통 DTO로 변환하고 펀딩비를 역산한다")
    void getClosedPnl_convertsAndCalcsFundingFee() {
        stubApiKey(1L);
        // 매매차익 1.0 - 수수료 0.1106 - 펀딩 지급 0.01 = closedPnl 0.8794
        given(positionClient.getClosedPnl(eq("linear"), eq("BTCUSDT"), eq(50), anyLong()))
                .willReturn(ResponseEntity.ok(makeClosedPnlPage(
                        makeClosedPnl("other-order", "Sell", "200", "210", "0.11", "0.1155", "9.7745"),
                        makeClosedPnl("close-order-1", "Sell", "100", "101", "0.055", "0.0556", "0.8794"))));

        ClosedPnlDTO result = client.getClosedPnl(closeQuery(), 1L);

        assertThat(result.getOrderId()).isEqualTo("close-order-1");
        assertThat(new BigDecimal(result.getClosedPnl())).isEqualByComparingTo("0.8794");
        assertThat(new BigDecimal(result.getFundingFee())).isEqualByComparingTo("0.01");
    }

    @Test
    @DisplayName("getClosedPnl: 숏 청산(Buy)은 진입가치 - 청산가치로 매매차익을 잡아 펀딩비를 역산한다")
    void getClosedPnl_shortClose_calcsFundingFee() {
        stubApiKey(1L);
        // 매매차익 (101 - 100) 1.0 - 수수료 0.1106 - 펀딩 지급 0.01 = closedPnl 0.8794
        given(positionClient.getClosedPnl(eq("linear"), eq("BTCUSDT"), eq(50), anyLong()))
                .willReturn(ResponseEntity.ok(makeClosedPnlPage(
                        makeClosedPnl("close-order-1", "Buy", "101", "100", "0.055", "0.0556", "0.8794"))));

        ClosedPnlDTO result = client.getClosedPnl(closeQuery(), 1L);

        assertThat(new BigDecimal(result.getFundingFee())).isEqualByComparingTo("0.01");
    }

    @Test
    @DisplayName("getClosedPnl: 주문 ID 일치 건이 없으면(미반영) 2회 조회 후 null을 반환한다")
    void getClosedPnl_notMatchedReturnsNull() {
        stubApiKey(1L);
        given(positionClient.getClosedPnl(anyString(), anyString(), anyInt(), anyLong()))
                .willReturn(ResponseEntity.ok(makeClosedPnlPage(
                        makeClosedPnl("other-order", "Sell", "100", "101", "0.055", "0.0556", "0.8794"))));

        assertThat(client.getClosedPnl(closeQuery(), 1L)).isNull();
        then(positionClient).should(times(2)).getClosedPnl(anyString(), anyString(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("getClosedPnl: 에러 응답이 계속되면 null을 반환한다")
    void getClosedPnl_errorReturnsNull() {
        stubApiKey(1L);
        GetClosedPnlResponse error = makeClosedPnlPage();
        error.setRetCode("10001");
        given(positionClient.getClosedPnl(anyString(), anyString(), anyInt(), anyLong()))
                .willReturn(ResponseEntity.ok(error));

        assertThat(client.getClosedPnl(closeQuery(), 1L)).isNull();
        then(positionClient).should(times(2)).getClosedPnl(anyString(), anyString(), anyInt(), anyLong());
    }

    // ─────────────────────────────────────────────
    // findLastClose (놓친 외부 청산 재조정)
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("findLastClose: 기준 시각 이후 가장 최근 청산 건을 찾고 체결 내역으로 청산 방식을 판정한다")
    void findLastClose_latestAfterSince_resolvesType() {
        stubApiKey(1L);
        given(positionClient.getClosedPnl(anyString(), eq("BTCUSDT"), anyInt(), anyLong()))
                .willReturn(ResponseEntity.ok(makeClosedPnlPage(
                        closedAt("close-b", 2500L, "50500"), closedAt("old-order", 1000L, "49000"),
                        closedAt("close-a", 3000L, "51000"))));
        GetExecutionListResponse.ExecutionInfo exec = makeExec("51000", "0.001", "0.03", "0.001");
        exec.setExecType("Trade");
        exec.setStopOrderType("TakeProfit");
        exec.setCreateType("CreateByTakeProfit");
        exec.setOrderLinkId("");
        given(tradeClient.getExecutionList(anyString(), eq("BTCUSDT"), eq("close-a"), anyInt(), any()))
                .willReturn(ResponseEntity.ok(makeExecutionPage(null, exec)));

        PositionCloseDTO result = client.findLastClose(1L, "BTCUSDT", 2000L);

        assertThat(result.getOrderId()).isEqualTo("close-a");
        assertThat(result.getOrderLinkId()).isEqualTo("");
        assertThat(result.getExecPrice()).isEqualTo("51000");
        assertThat(result.getCloseType()).isEqualTo(PositionCloseType.TAKE_PROFIT);
        assertThat(result.getUserId()).isEqualTo(1L);
        assertThat(result.getExchangeType()).isEqualTo(ExchangeType.BYBIT);
        assertThat(result.getSymbol()).isEqualTo("BTCUSDT");
    }

    @Test
    @DisplayName("findLastClose: 조회 시작 시각은 Bybit 조회 한도(7일) 이전으로 내려가지 않는다")
    void findLastClose_startTimeClampedTo7Days() {
        stubApiKey(1L);
        long now = 10L * 24 * 60 * 60 * 1000;
        given(clock.millis()).willReturn(now);
        given(positionClient.getClosedPnl(anyString(), anyString(), anyInt(), anyLong()))
                .willReturn(ResponseEntity.ok(makeClosedPnlPage()));

        client.findLastClose(1L, "BTCUSDT", 0L);

        then(positionClient).should().getClosedPnl("linear", "BTCUSDT", 50, now - 7L * 24 * 60 * 60 * 1000);
    }

    @Test
    @DisplayName("findLastClose: 체결 내역이 없으면 주문 ID는 유지하고 청산 방식은 기타로 판정한다")
    void findLastClose_noExecutions_other() {
        stubApiKey(1L);
        given(positionClient.getClosedPnl(anyString(), anyString(), anyInt(), anyLong()))
                .willReturn(ResponseEntity.ok(makeClosedPnlPage(closedAt("close-a", 3000L, "51000"))));
        given(tradeClient.getExecutionList(anyString(), anyString(), anyString(), anyInt(), any()))
                .willReturn(ResponseEntity.ok(makeEmptyExecutionListResponse()));

        PositionCloseDTO result = client.findLastClose(1L, "BTCUSDT", 2000L);

        assertThat(result.getOrderId()).isEqualTo("close-a");
        assertThat(result.getCloseType()).isEqualTo(PositionCloseType.OTHER);
    }

    @Test
    @DisplayName("findLastClose: 기준 시각 이후 청산 건이 없으면 주문 ID 없이 기타로 반환한다")
    void findLastClose_notFound_otherWithoutOrderId() {
        stubApiKey(1L);
        given(positionClient.getClosedPnl(anyString(), anyString(), anyInt(), anyLong()))
                .willReturn(ResponseEntity.ok(makeClosedPnlPage(closedAt("old-order", 1000L, "49000"))));

        PositionCloseDTO result = client.findLastClose(1L, "BTCUSDT", 2000L);

        assertThat(result.getOrderId()).isNull();
        assertThat(result.getCloseType()).isEqualTo(PositionCloseType.OTHER);
        then(tradeClient).should(never()).getExecutionList(any(), any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("findLastClose: 청산 내역 조회가 에러 응답이거나 예외면 null을 반환한다")
    void findLastClose_errorReturnsNull() {
        stubApiKey(1L);
        GetClosedPnlResponse error = makeClosedPnlPage();
        error.setRetCode("10001");
        given(positionClient.getClosedPnl(anyString(), anyString(), anyInt(), anyLong()))
                .willReturn(ResponseEntity.ok(error))
                .willThrow(new RuntimeException("timeout"));

        assertThat(client.findLastClose(1L, "BTCUSDT", 2000L)).isNull();
        assertThat(client.findLastClose(1L, "BTCUSDT", 2000L)).isNull();
    }

    // ─────────────────────────────────────────────
    // getPositionSize
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("getPositionSize: 심볼의 현재 포지션 수량을 반환한다 (포지션 없으면 0)")
    void getPositionSize_returnsSize() {
        stubApiKey(1L);
        given(positionClient.getPositionList("linear", "BTCUSDT"))
                .willReturn(ResponseEntity.ok(makePositionListResponse("0", "0.002")))
                .willReturn(ResponseEntity.ok(makePositionListResponse("0", "0")));

        assertThat(client.getPositionSize(1L, "BTCUSDT")).isEqualByComparingTo("0.002");
        assertThat(client.getPositionSize(1L, "BTCUSDT")).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("getPositionSize: 에러 응답이거나 호출 실패면 null을 반환한다")
    void getPositionSize_errorReturnsNull() {
        stubApiKey(1L);
        given(positionClient.getPositionList("linear", "BTCUSDT"))
                .willReturn(ResponseEntity.ok(makePositionListResponse("10001", "0")))
                .willThrow(new RuntimeException("timeout"));

        assertThat(client.getPositionSize(1L, "BTCUSDT")).isNull();
        assertThat(client.getPositionSize(1L, "BTCUSDT")).isNull();
    }

    // ─────────────────────────────────────────────
    // private 스트림 (연결 동기화)
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("syncPrivateStreams: 연결되지 않은 대상 사용자는 키를 복호화해 연결을 연다")
    void syncPrivateStreams_opensNewUser() {
        given(bybitPrivateWebSocketClient.getConnectedUserIds()).willReturn(Set.of());
        givenKey(1L, "encKey", "encSecret", "apiKey", "apiSecret");

        client.syncPrivateStreams(Set.of(1L));

        then(bybitPrivateWebSocketClient).should().open(1L, "apiKey", "apiSecret");
    }

    @Test
    @DisplayName("syncPrivateStreams: 대상에서 빠진 사용자의 연결은 닫는다")
    void syncPrivateStreams_closesRemovedUser() {
        given(bybitPrivateWebSocketClient.getConnectedUserIds()).willReturn(Set.of(1L));

        client.syncPrivateStreams(Set.of());

        then(bybitPrivateWebSocketClient).should().close(1L);
        then(bybitPrivateWebSocketClient).should(never()).open(any(), any(), any());
    }

    @Test
    @DisplayName("syncPrivateStreams: 이미 연결된 사용자는 다시 열지 않는다 (키 조회도 하지 않음)")
    void syncPrivateStreams_skipsAlreadyConnected() {
        given(bybitPrivateWebSocketClient.getConnectedUserIds()).willReturn(Set.of(1L));

        client.syncPrivateStreams(Set.of(1L));

        then(exchangeKeyService).shouldHaveNoInteractions();
        then(bybitPrivateWebSocketClient).should(never()).open(any(), any(), any());
        then(bybitPrivateWebSocketClient).should(never()).close(any());
    }

    @Test
    @DisplayName("syncPrivateStreams: 키가 없는 사용자는 건너뛰고 다른 사용자는 정상 연결한다")
    void syncPrivateStreams_missingKey_skipsOnlyThatUser() {
        given(bybitPrivateWebSocketClient.getConnectedUserIds()).willReturn(Set.of());
        given(exchangeKeyService.findKey(1L, ExchangeType.BYBIT))
                .willThrow(new CustomException(ExceptionMessage.API_KEY_NOT_FOUND));
        givenKey(2L, "encKey2", "encSecret2", "apiKey2", "apiSecret2");

        client.syncPrivateStreams(new LinkedHashSet<>(List.of(1L, 2L)));

        then(bybitPrivateWebSocketClient).should().open(2L, "apiKey2", "apiSecret2");
        then(bybitPrivateWebSocketClient).should(never()).open(eq(1L), any(), any());
    }

    // ─────────────────────────────────────────────
    // private 스트림 (포지션 청산 체결 감지)
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("init: private WS 클라이언트에 체결 콜백을 등록한다")
    void init_registersExecutionCallback() {
        client.init();

        then(bybitPrivateWebSocketClient).should().addExecutionCallback(any());
    }

    @Test
    @DisplayName("청산 체결(closedSize>0)의 마지막 건이면 포지션 청산 이벤트를 전달한다")
    void execution_closeFill_emitsEvent() {
        List<PositionCloseDTO> received = captureCloseEvents();

        execCallback().accept(7L, makeWsExec("Trade", "0.01", "0", "TakeProfit", "CreateByTakeProfit"));

        assertThat(received).hasSize(1);
        PositionCloseDTO event = received.get(0);
        assertThat(event.getUserId()).isEqualTo(7L);
        assertThat(event.getExchangeType()).isEqualTo(ExchangeType.BYBIT);
        assertThat(event.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(event.getOrderId()).isEqualTo("ext-1");
        assertThat(event.getOrderLinkId()).isEqualTo("");
        assertThat(event.getExecPrice()).isEqualTo("51000");
        assertThat(event.getCloseType()).isEqualTo(PositionCloseType.TAKE_PROFIT);
    }

    @Test
    @DisplayName("청산 종류: 스톱 주문 유형·체결 유형으로 익절/손절/강제청산/기타를 구분한다")
    void execution_closeTypeMapping() {
        List<PositionCloseDTO> received = captureCloseEvents();
        BiConsumer<Long, WebSocketExecutionDTO.ExecutionData> cb = execCallback();

        cb.accept(1L, makeWsExec("Trade", "0.01", "0", "StopLoss", "CreateByStopLoss"));
        cb.accept(1L, makeWsExec("BustTrade", "0.01", "0", "", "CreateByLiq"));
        cb.accept(1L, makeWsExec("Trade", "0.01", "0", "", "CreateByClosing"));
        cb.accept(1L, makeWsExec("AdlTrade", "0.01", "0", "", "CreateByAdl_PassThrough"));

        assertThat(received).extracting(PositionCloseDTO::getCloseType).containsExactly(
                PositionCloseType.STOP_LOSS, PositionCloseType.LIQUIDATION,
                PositionCloseType.OTHER, PositionCloseType.OTHER);
    }

    @Test
    @DisplayName("진입 체결·부분 체결 중간 건·펀딩비 정산은 청산 이벤트로 전달하지 않는다")
    void execution_nonCloseFills_ignored() {
        List<PositionCloseDTO> received = captureCloseEvents();
        BiConsumer<Long, WebSocketExecutionDTO.ExecutionData> cb = execCallback();

        cb.accept(1L, makeWsExec("Trade", "0", "0", "", "CreateByUser"));        // 진입 체결
        cb.accept(1L, makeWsExec("Trade", "0.005", "0.005", "", "CreateByUser")); // 부분 체결 (잔량 있음)
        cb.accept(1L, makeWsExec("Funding", "0", "0", "", ""));                  // 펀딩비

        assertThat(received).isEmpty();
    }

    // ─────────────────────────────────────────────
    // 헬퍼 메서드
    // ─────────────────────────────────────────────

    private CreateOrderRequest capturedOrder() {
        ArgumentCaptor<CreateOrderRequest> captor = ArgumentCaptor.forClass(CreateOrderRequest.class);
        then(tradeClient).should().createOrder(captor.capture());
        return captor.getValue();
    }

    private static OrderRequestDTO order(String clientOrderId) {
        return OrderRequestDTO.builder().symbol("BTCUSDT").side(OrderSide.BUY).qty("0.01")
                .clientOrderId(clientOrderId).build();
    }

    /** 포지션 청산 리스너를 등록하고 수신 이벤트 목록을 반환 */
    private List<PositionCloseDTO> captureCloseEvents() {
        List<PositionCloseDTO> received = new ArrayList<>();
        client.addPositionCloseListener(received::add);
        return received;
    }

    /** init 시 private WS 클라이언트에 등록된 체결 콜백 */
    @SuppressWarnings("unchecked")
    private BiConsumer<Long, WebSocketExecutionDTO.ExecutionData> execCallback() {
        client.init();
        ArgumentCaptor<BiConsumer<Long, WebSocketExecutionDTO.ExecutionData>> captor =
                ArgumentCaptor.forClass(BiConsumer.class);
        then(bybitPrivateWebSocketClient).should().addExecutionCallback(captor.capture());
        return captor.getValue();
    }

    private WebSocketExecutionDTO.ExecutionData makeWsExec(String execType, String closedSize, String leavesQty,
                                                           String stopOrderType, String createType) {
        WebSocketExecutionDTO.ExecutionData d = new WebSocketExecutionDTO.ExecutionData();
        d.setSymbol("BTCUSDT");
        d.setOrderId("ext-1");
        d.setOrderLinkId("");
        d.setExecId("e-" + execType + "-" + createType);
        d.setExecType(execType);
        d.setExecPrice("51000");
        d.setExecQty("0.01");
        d.setClosedSize(closedSize);
        d.setLeavesQty(leavesQty);
        d.setStopOrderType(stopOrderType);
        d.setCreateType(createType);
        return d;
    }

    private CancelOrderResponse makeCancelOrderResponse(String retCode) {
        CancelOrderResponse response = new CancelOrderResponse();
        response.setRetCode(retCode);
        response.setRetMsg("0".equals(retCode) ? "OK" : "error");
        return response;
    }

    private CreateOrderResponse makeCreateOrderResponse(String retCode, String orderId) {
        CreateOrderResponse response = new CreateOrderResponse();
        response.setRetCode(retCode);
        response.setRetMsg("0".equals(retCode) ? "OK" : "error");
        if (orderId != null) {
            CreateOrderResponse.Result result = new CreateOrderResponse.Result();
            result.setOrderId(orderId);
            response.setResult(result);
        }
        return response;
    }

    private GetExecutionListResponse.ExecutionInfo makeExec(String execPrice, String execQty,
                                                           String execFee, String orderQty) {
        GetExecutionListResponse.ExecutionInfo info = new GetExecutionListResponse.ExecutionInfo();
        info.setSymbol("BTCUSDT");
        info.setOrderId("order-123");
        info.setExecPrice(execPrice);
        info.setExecQty(execQty);
        info.setExecFee(execFee);
        info.setOrderQty(orderQty);
        return info;
    }

    private GetExecutionListResponse makeExecutionPage(String nextPageCursor,
                                                       GetExecutionListResponse.ExecutionInfo... infos) {
        GetExecutionListResponse.Result result = new GetExecutionListResponse.Result();
        result.setList(List.of(infos));
        result.setNextPageCursor(nextPageCursor);

        GetExecutionListResponse response = new GetExecutionListResponse();
        response.setRetCode("0");
        response.setRetMsg("OK");
        response.setResult(result);
        return response;
    }

    private GetExecutionListResponse makeEmptyExecutionListResponse() {
        return makeExecutionPage(null);
    }

    private GetClosedPnlResponse.ClosedPnlInfo makeClosedPnl(String orderId, String side,
                                                             String cumEntryValue, String cumExitValue,
                                                             String openFee, String closeFee, String closedPnl) {
        GetClosedPnlResponse.ClosedPnlInfo info = new GetClosedPnlResponse.ClosedPnlInfo();
        info.setSymbol("BTCUSDT");
        info.setOrderId(orderId);
        info.setSide(side);
        info.setCumEntryValue(cumEntryValue);
        info.setCumExitValue(cumExitValue);
        info.setOpenFee(openFee);
        info.setCloseFee(closeFee);
        info.setClosedPnl(closedPnl);
        return info;
    }

    private GetClosedPnlResponse makeClosedPnlPage(GetClosedPnlResponse.ClosedPnlInfo... infos) {
        GetClosedPnlResponse.Result result = new GetClosedPnlResponse.Result();
        result.setList(List.of(infos));

        GetClosedPnlResponse response = new GetClosedPnlResponse();
        response.setRetCode("0");
        response.setRetMsg("OK");
        response.setResult(result);
        return response;
    }

    private GetClosedPnlResponse.ClosedPnlInfo closedAt(String orderId, long createdTime, String avgExitPrice) {
        GetClosedPnlResponse.ClosedPnlInfo info = makeClosedPnl(orderId, "Sell", "100", "101", "0.05", "0.05", "0.9");
        info.setCreatedTime(String.valueOf(createdTime));
        info.setAvgExitPrice(avgExitPrice);
        return info;
    }

    private GetPositionListResponse makePositionListResponse(String retCode, String size) {
        GetPositionListResponse.Result.PositionInfo info = new GetPositionListResponse.Result.PositionInfo();
        info.setSymbol("BTCUSDT");
        info.setSize(size);
        GetPositionListResponse.Result result = new GetPositionListResponse.Result();
        result.setList(List.of(info));
        GetPositionListResponse response = new GetPositionListResponse();
        response.setRetCode(retCode);
        response.setResult(result);
        return response;
    }

    private GetOpenOrdersResponse makeOpenOrdersResponse(String orderStatus, String cumExecQty) {
        GetOpenOrdersResponse.OrderInfo info = new GetOpenOrdersResponse.OrderInfo();
        info.setOrderId("order-999");
        info.setOrderLinkId("q1-E-1");
        info.setSymbol("BTCUSDT");
        info.setOrderStatus(orderStatus);
        info.setCumExecQty(cumExecQty);

        GetOpenOrdersResponse.Result result = new GetOpenOrdersResponse.Result();
        result.setList(List.of(info));

        GetOpenOrdersResponse response = new GetOpenOrdersResponse();
        response.setRetCode("0");
        response.setRetMsg("OK");
        response.setResult(result);
        return response;
    }

    private GetOpenOrdersResponse makeEmptyOpenOrdersResponse() {
        GetOpenOrdersResponse.Result result = new GetOpenOrdersResponse.Result();
        result.setList(Collections.emptyList());

        GetOpenOrdersResponse response = new GetOpenOrdersResponse();
        response.setRetCode("0");
        response.setRetMsg("OK");
        response.setResult(result);
        return response;
    }

    private SetMarginModeResponse makeMarginModeResponse(String retCode) {
        SetMarginModeResponse response = new SetMarginModeResponse();
        response.setRetCode(retCode);
        response.setRetMsg("0".equals(retCode) ? "OK" : "error");
        return response;
    }

    // Bybit는 orderId·symbol만 사용 (entryTime·entryFee는 넣어도 무시)
    private ClosedPnlQueryDTO closeQuery() {
        return ClosedPnlQueryDTO.builder().exchangeType(ExchangeType.BYBIT).orderId("close-order-1").symbol("BTCUSDT")
                .entryTime(1L).entryFee(new BigDecimal("0.1")).build();
    }

    private SetLeverageResponse makeLeverageResponse(String retCode) {
        SetLeverageResponse response = new SetLeverageResponse();
        response.setRetCode(retCode);
        response.setRetMsg("0".equals(retCode) ? "OK" : "error");
        return response;
    }

    // 읽기 타임아웃 등 I/O 오류 시 Feign이 던지는 예외
    private RetryableException makeTimeoutException() {
        Request request = Request.create(Request.HttpMethod.POST, "/v5/order/create",
                Collections.emptyMap(), null, StandardCharsets.UTF_8, null);
        return new RetryableException(-1, "Read timed out", Request.HttpMethod.POST, (Long) null, request);
    }

    // HTTP 오류 응답 시 Feign이 던지는 예외 (4xx: FeignClientException, 5xx: FeignServerException)
    private FeignException makeHttpException(int status) {
        Request request = Request.create(Request.HttpMethod.POST, "/v5/order/create",
                Collections.emptyMap(), null, StandardCharsets.UTF_8, null);
        Response response = Response.builder().status(status).reason("error")
                .request(request).headers(Collections.emptyMap()).build();
        return FeignException.errorStatus("TradeClient#createOrder", response);
    }

    private void stubApiKey(Long userId) {
        given(exchangeKeyService.findKey(userId, ExchangeType.BYBIT)).willReturn(ExchangeKeyDTO.builder()
                .userId(userId).exchangeType(ExchangeType.BYBIT).apiKey("encKey").apiSecret("encSecret").build());
        given(aesEncryptor.decrypt(anyString())).willReturn("raw");
    }

    private void givenKey(Long userId, String encKey, String encSecret, String apiKey, String apiSecret) {
        given(exchangeKeyService.findKey(userId, ExchangeType.BYBIT)).willReturn(ExchangeKeyDTO.builder()
                .userId(userId).exchangeType(ExchangeType.BYBIT).apiKey(encKey).apiSecret(encSecret).build());
        given(aesEncryptor.decrypt(encKey)).willReturn(apiKey);
        given(aesEncryptor.decrypt(encSecret)).willReturn(apiSecret);
    }

    @Test
    @DisplayName("getTakerFeeRate: Bybit Linear(USDT 선물) taker 수수료율 0.055%")
    void takerFeeRate() {
        assertThat(client.getTakerFeeRate()).isEqualByComparingTo("0.00055");
    }

    @Test
    @DisplayName("getMakerFeeRate: Bybit Linear(USDT 선물) maker 수수료율 0.02%")
    void makerFeeRate() {
        assertThat(client.getMakerFeeRate()).isEqualByComparingTo("0.0002");
    }
}
