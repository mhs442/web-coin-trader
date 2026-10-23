package com.coin.webcointrader.trade.service;

import com.coin.webcointrader.bybit.client.position.PositionClient;
import com.coin.webcointrader.bybit.client.privatews.BybitExecutionWaiter;
import com.coin.webcointrader.bybit.client.trade.TradeClient;
import com.coin.webcointrader.bybit.dto.request.CreateOrderRequest;
import com.coin.webcointrader.bybit.dto.request.SetLeverageRequest;
import com.coin.webcointrader.bybit.dto.response.CreateOrderResponse;
import com.coin.webcointrader.bybit.dto.response.GetClosedPnlResponse;
import com.coin.webcointrader.bybit.dto.response.GetExecutionListResponse;
import com.coin.webcointrader.bybit.dto.response.GetOpenOrdersResponse;
import com.coin.webcointrader.bybit.dto.response.GetPositionListResponse;
import com.coin.webcointrader.bybit.dto.response.SetLeverageResponse;
import com.coin.webcointrader.bybit.dto.response.SetMarginModeResponse;
import com.coin.webcointrader.login.dto.ExchangeKeyDTO;
import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.enums.PositionCloseType;
import com.coin.webcointrader.common.exchange.PositionCloseDTO;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.login.service.ExchangeKeyService;
import com.coin.webcointrader.common.util.AesEncryptor;
import com.coin.webcointrader.common.util.UserApiKeyContext;
import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.RetryableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class TradeServiceTest {

    @InjectMocks
    private TradeService tradeService;

    @Mock
    private TradeClient tradeClient;

    @Mock
    private PositionClient positionClient;

    @Mock
    private TradeHistoryService tradeHistoryService;

    @Mock
    private ExchangeKeyService exchangeKeyService;

    @Mock
    private AesEncryptor aesEncryptor;

    @Mock
    private Clock clock;

    @Mock
    private BybitExecutionWaiter bybitExecutionWaiter;

    // 마진 모드/레버리지 캐시 유효 시간 (10분)
    private static final long SETTING_CACHE_TTL_MS = 10 * 60 * 1000L;

    @AfterEach
    void clearContext() {
        UserApiKeyContext.clear();
    }

    @Test
    @DisplayName("placeOrder: 유효한 요청이면 주문을 실행하고 응답을 반환한다")
    void placeOrder_success() {
        // given
        Long userId = 1L;
        CreateOrderRequest request = CreateOrderRequest.builder()
                .category("linear")
                .symbol("BTCUSDT")
                .side("Buy")
                .orderType("Market")
                .qty("0.01")
                .build();

        ExchangeKeyDTO key = makeExchangeKey(userId, "encKey", "encSecret");
        CreateOrderResponse response = new CreateOrderResponse();
        response.setRetCode("0");
        response.setRetMsg("OK");

        given(exchangeKeyService.findKey(userId, ExchangeType.BYBIT)).willReturn(key);
        given(aesEncryptor.decrypt("encKey")).willReturn("rawApiKey");
        given(aesEncryptor.decrypt("encSecret")).willReturn("rawApiSecret");
        given(tradeClient.createOrder(request)).willReturn(ResponseEntity.ok(response));

        // when
        CreateOrderResponse result = tradeService.placeOrder(request, userId);

        // then
        assertThat(result).isNotNull();
        then(tradeClient).should().createOrder(request);
    }

    @Test
    @DisplayName("placeOrder: category가 없으면 LINEAR로 기본 설정하여 주문한다")
    void placeOrder_defaultCategory() {
        // given
        Long userId = 1L;
        CreateOrderRequest request = CreateOrderRequest.builder()
                .symbol("BTCUSDT")
                .side("Buy")
                .orderType("Market")
                .qty("0.01")
                .build();  // category 없음

        ExchangeKeyDTO key = makeExchangeKey(userId, "encKey", "encSecret");
        CreateOrderResponse response = new CreateOrderResponse();
        response.setRetCode("0");
        response.setRetMsg("OK");

        given(exchangeKeyService.findKey(userId, ExchangeType.BYBIT)).willReturn(key);
        given(aesEncryptor.decrypt(anyString())).willReturn("raw");
        given(tradeClient.createOrder(any(CreateOrderRequest.class))).willReturn(ResponseEntity.ok(response));

        // when
        tradeService.placeOrder(request, userId);

        // then - category가 "linear"로 설정된 request로 호출됨
        then(tradeClient).should().createOrder(argThat(r -> "linear".equals(r.getCategory())));
    }

    @Test
    @DisplayName("placeOrder: category 기본 설정 시 orderLinkId·marketUnit·TP/SL 등 나머지 필드를 유지한다")
    void placeOrder_defaultCategory_keepsOtherFields() {
        // given - category 없이 orderLinkId·TP/SL을 담은 주문
        Long userId = 1L;
        CreateOrderRequest request = CreateOrderRequest.builder()
                .symbol("BTCUSDT")
                .side("Buy")
                .orderType("Market")
                .qty("100")
                .marketUnit("quoteCoin")
                .orderLinkId("q1-E-1700000000000")
                .takeProfit("55000")
                .stopLoss("45000")
                .tpslMode("Full")
                .build();  // category 없음

        ExchangeKeyDTO key = makeExchangeKey(userId, "encKey", "encSecret");
        CreateOrderResponse response = new CreateOrderResponse();
        response.setRetCode("0");
        response.setRetMsg("OK");

        given(exchangeKeyService.findKey(userId, ExchangeType.BYBIT)).willReturn(key);
        given(aesEncryptor.decrypt(anyString())).willReturn("raw");
        given(tradeClient.createOrder(any(CreateOrderRequest.class))).willReturn(ResponseEntity.ok(response));

        // when
        tradeService.placeOrder(request, userId);

        // then - category만 채워지고 나머지 필드는 그대로 전달됨
        ArgumentCaptor<CreateOrderRequest> captor = ArgumentCaptor.forClass(CreateOrderRequest.class);
        then(tradeClient).should().createOrder(captor.capture());
        CreateOrderRequest sent = captor.getValue();
        assertThat(sent.getCategory()).isEqualTo("linear");
        assertThat(sent.getMarketUnit()).isEqualTo("quoteCoin");
        assertThat(sent.getOrderLinkId()).isEqualTo("q1-E-1700000000000");
        assertThat(sent.getTakeProfit()).isEqualTo("55000");
        assertThat(sent.getStopLoss()).isEqualTo("45000");
        assertThat(sent.getTpslMode()).isEqualTo("Full");
    }

    @Test
    @DisplayName("placeOrder: 거래소 API 키를 찾을 수 없으면 CustomException(API_KEY_NOT_FOUND) 발생")
    void placeOrder_apiKeyNotFound() {
        // given
        Long userId = 99L;
        CreateOrderRequest request = CreateOrderRequest.builder()
                .category("linear")
                .symbol("BTCUSDT")
                .side("Buy")
                .orderType("Market")
                .qty("0.01")
                .build();

        given(exchangeKeyService.findKey(userId, ExchangeType.BYBIT))
                .willThrow(new CustomException(ExceptionMessage.API_KEY_NOT_FOUND));

        // when & then
        assertThatThrownBy(() -> tradeService.placeOrder(request, userId))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining(ExceptionMessage.API_KEY_NOT_FOUND.getMessage());
    }

    @Test
    @DisplayName("placeOrder: 주문 실패 시에도 finally에서 UserApiKeyContext가 정리된다")
    void placeOrder_clearsContextOnException() {
        // given
        Long userId = 1L;
        CreateOrderRequest request = CreateOrderRequest.builder()
                .category("linear")
                .symbol("BTCUSDT")
                .side("Buy")
                .orderType("Market")
                .qty("0.01")
                .build();

        ExchangeKeyDTO key = makeExchangeKey(userId, "encKey", "encSecret");
        given(exchangeKeyService.findKey(userId, ExchangeType.BYBIT)).willReturn(key);
        given(aesEncryptor.decrypt(anyString())).willReturn("raw");
        given(tradeClient.createOrder(any())).willThrow(new RuntimeException("API 오류"));

        // when & then
        assertThatThrownBy(() -> tradeService.placeOrder(request, userId))
                .isInstanceOf(RuntimeException.class);

        // context가 정리됨
        assertThat(UserApiKeyContext.getApiKey()).isNull();
        assertThat(UserApiKeyContext.getApiSecret()).isNull();
    }

    // ─────────────────────────────────────────────
    // placeOrder 결과 불명확(타임아웃·5xx) 시 orderLinkId 조회 테스트
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("placeOrder: 타임아웃이어도 orderLinkId 조회 결과 체결됐으면 성공 응답을 반환한다")
    void placeOrder_타임아웃_체결확인시_성공() {
        // given
        Long userId = 1L;
        stubApiKey(userId);
        CreateOrderRequest request = makeOrderRequest("q1-E-1700000000000");
        given(tradeClient.createOrder(any())).willThrow(makeTimeoutException());
        given(tradeClient.getOpenOrders("linear", "BTCUSDT", "q1-E-1700000000000"))
                .willReturn(ResponseEntity.ok(makeOpenOrdersResponse("Filled", "0.01")));

        // when
        CreateOrderResponse result = tradeService.placeOrder(request, userId);

        // then - 조회된 orderId로 성공 응답 구성 (이후 체결 조회가 이어지도록)
        assertThat(result.getRetCode()).isEqualTo("0");
        assertThat(result.getResult().getOrderId()).isEqualTo("order-999");
        assertThat(result.getResult().getOrderLinkId()).isEqualTo("q1-E-1700000000000");
    }

    @Test
    @DisplayName("placeOrder: 5xx 응답이어도 orderLinkId 조회 결과 체결됐으면 성공 응답을 반환한다")
    void placeOrder_5xx_체결확인시_성공() {
        // given
        Long userId = 1L;
        stubApiKey(userId);
        CreateOrderRequest request = makeOrderRequest("q1-E-1700000000000");
        given(tradeClient.createOrder(any())).willThrow(makeHttpException(502));
        given(tradeClient.getOpenOrders(anyString(), anyString(), anyString()))
                .willReturn(ResponseEntity.ok(makeOpenOrdersResponse("Filled", "0.01")));

        // when
        CreateOrderResponse result = tradeService.placeOrder(request, userId);

        // then
        assertThat(result.getResult().getOrderId()).isEqualTo("order-999");
    }

    @Test
    @DisplayName("placeOrder: 타임아웃 후 조회해도 주문이 없으면 재조회 후 원래 예외를 던진다")
    void placeOrder_타임아웃_주문없음_예외() {
        // given
        Long userId = 1L;
        stubApiKey(userId);
        CreateOrderRequest request = makeOrderRequest("q1-E-1700000000000");
        given(tradeClient.createOrder(any())).willThrow(makeTimeoutException());
        given(tradeClient.getOpenOrders(anyString(), anyString(), anyString()))
                .willReturn(ResponseEntity.ok(makeEmptyOpenOrdersResponse()));

        // when & then
        assertThatThrownBy(() -> tradeService.placeOrder(request, userId))
                .isInstanceOf(RetryableException.class);
        // 거래소 반영 지연 대비 2회 조회
        then(tradeClient).should(times(2)).getOpenOrders(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("placeOrder: 타임아웃 후 조회 결과 Rejected(미체결)면 원래 예외를 던진다")
    void placeOrder_타임아웃_거부됨_예외() {
        // given
        Long userId = 1L;
        stubApiKey(userId);
        CreateOrderRequest request = makeOrderRequest("q1-E-1700000000000");
        given(tradeClient.createOrder(any())).willThrow(makeTimeoutException());
        given(tradeClient.getOpenOrders(anyString(), anyString(), anyString()))
                .willReturn(ResponseEntity.ok(makeOpenOrdersResponse("Rejected", "0")));

        // when & then
        assertThatThrownBy(() -> tradeService.placeOrder(request, userId))
                .isInstanceOf(RetryableException.class);
    }

    @Test
    @DisplayName("placeOrder: 타임아웃 후 조회 자체가 실패하면 원래 예외를 던진다")
    void placeOrder_타임아웃_조회실패_원래예외() {
        // given
        Long userId = 1L;
        stubApiKey(userId);
        CreateOrderRequest request = makeOrderRequest("q1-E-1700000000000");
        given(tradeClient.createOrder(any())).willThrow(makeTimeoutException());
        given(tradeClient.getOpenOrders(anyString(), anyString(), anyString()))
                .willThrow(new RuntimeException("조회 오류"));

        // when & then
        assertThatThrownBy(() -> tradeService.placeOrder(request, userId))
                .isInstanceOf(RetryableException.class);
    }

    @Test
    @DisplayName("placeOrder: 4xx 응답은 거래소 거부가 확실하므로 조회 없이 예외를 던진다")
    void placeOrder_4xx_조회안함() {
        // given
        Long userId = 1L;
        stubApiKey(userId);
        CreateOrderRequest request = makeOrderRequest("q1-E-1700000000000");
        given(tradeClient.createOrder(any())).willThrow(makeHttpException(400));

        // when & then
        assertThatThrownBy(() -> tradeService.placeOrder(request, userId))
                .isInstanceOf(FeignException.class);
        then(tradeClient).should(never()).getOpenOrders(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("placeOrder: orderLinkId가 없는 주문(수동 주문)은 타임아웃이어도 조회 없이 예외를 던진다")
    void placeOrder_orderLinkId없음_조회안함() {
        // given
        Long userId = 1L;
        stubApiKey(userId);
        CreateOrderRequest request = makeOrderRequest(null);
        given(tradeClient.createOrder(any())).willThrow(makeTimeoutException());

        // when & then
        assertThatThrownBy(() -> tradeService.placeOrder(request, userId))
                .isInstanceOf(RetryableException.class);
        then(tradeClient).should(never()).getOpenOrders(any(), any(), any());
    }

    // ─────────────────────────────────────────────
    // getExecution 테스트
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("getExecution: 정상 응답이면 실체결 정보를 반환한다")
    void getExecution_성공_실체결데이터반환() {
        // given
        Long userId = 1L;
        ExchangeKeyDTO key = makeExchangeKey(userId, "encKey", "encSecret");
        given(exchangeKeyService.findKey(userId, ExchangeType.BYBIT)).willReturn(key);
        given(aesEncryptor.decrypt(anyString())).willReturn("raw");

        GetExecutionListResponse response = makeExecutionListResponse("50100.50", "0.0551");
        given(tradeClient.getExecutionList(anyString(), anyString(), anyString(), anyInt(), any()))
                .willReturn(ResponseEntity.ok(response));

        // when
        GetExecutionListResponse.ExecutionInfo result =
                tradeService.getExecution("order-123", "BTCUSDT", userId);

        // then
        assertThat(result).isNotNull();
        assertThat(new BigDecimal(result.getExecPrice())).isEqualByComparingTo("50100.50");
        assertThat(new BigDecimal(result.getExecFee())).isEqualByComparingTo("0.0551");
    }

    @Test
    @DisplayName("getExecution(orderLinkId): WS 체결을 받으면 REST 체결 조회를 호출하지 않는다")
    void getExecution_ws수신시_REST미호출() {
        // given
        GetExecutionListResponse.ExecutionInfo wsExec = new GetExecutionListResponse.ExecutionInfo();
        wsExec.setExecPrice("50300.5");
        wsExec.setExecFee("0.0553");
        given(bybitExecutionWaiter.await("q1-E-1", 2000L)).willReturn(wsExec);

        // when
        GetExecutionListResponse.ExecutionInfo result =
                tradeService.getExecution("order-123", "q1-E-1", "BTCUSDT", 1L);

        // then
        assertThat(result).isSameAs(wsExec);
        then(tradeClient).should(never()).getExecutionList(any(), any(), any(), anyInt(), any());
        then(exchangeKeyService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("getExecution(orderLinkId): WS 체결이 없으면 REST 체결 조회로 fallback한다")
    void getExecution_ws미수신시_REST_fallback() {
        // given
        Long userId = 1L;
        given(bybitExecutionWaiter.await("q1-E-1", 2000L)).willReturn(null);
        given(exchangeKeyService.findKey(userId, ExchangeType.BYBIT)).willReturn(makeExchangeKey(userId, "encKey", "encSecret"));
        given(aesEncryptor.decrypt(anyString())).willReturn("raw");
        given(tradeClient.getExecutionList(anyString(), anyString(), anyString(), anyInt(), any()))
                .willReturn(ResponseEntity.ok(makeExecutionListResponse("50100.50", "0.0551")));

        // when
        GetExecutionListResponse.ExecutionInfo result =
                tradeService.getExecution("order-123", "q1-E-1", "BTCUSDT", userId);

        // then
        assertThat(new BigDecimal(result.getExecPrice())).isEqualByComparingTo("50100.50");
    }

    @Test
    @DisplayName("getExecution: 1차 빈 응답 후 2차 성공 시 실체결 정보를 반환한다")
    void getExecution_1차빈응답_2차성공시반환() {
        // given
        Long userId = 1L;
        ExchangeKeyDTO key = makeExchangeKey(userId, "encKey", "encSecret");
        given(exchangeKeyService.findKey(userId, ExchangeType.BYBIT)).willReturn(key);
        given(aesEncryptor.decrypt(anyString())).willReturn("raw");

        GetExecutionListResponse empty = makeEmptyExecutionListResponse();
        GetExecutionListResponse success = makeExecutionListResponse("50200.00", "0.0552");
        given(tradeClient.getExecutionList(anyString(), anyString(), anyString(), anyInt(), any()))
                .willReturn(ResponseEntity.ok(empty))
                .willReturn(ResponseEntity.ok(success));

        // when
        GetExecutionListResponse.ExecutionInfo result =
                tradeService.getExecution("order-123", "BTCUSDT", userId);

        // then
        assertThat(result).isNotNull();
        assertThat(new BigDecimal(result.getExecPrice())).isEqualByComparingTo("50200.00");
    }

    @Test
    @DisplayName("getExecution: 2회 모두 빈 응답이면 null을 반환한다")
    void getExecution_2회빈응답_null반환() {
        // given
        Long userId = 1L;
        ExchangeKeyDTO key = makeExchangeKey(userId, "encKey", "encSecret");
        given(exchangeKeyService.findKey(userId, ExchangeType.BYBIT)).willReturn(key);
        given(aesEncryptor.decrypt(anyString())).willReturn("raw");

        GetExecutionListResponse empty = makeEmptyExecutionListResponse();
        given(tradeClient.getExecutionList(anyString(), anyString(), anyString(), anyInt(), any()))
                .willReturn(ResponseEntity.ok(empty));

        // when
        GetExecutionListResponse.ExecutionInfo result =
                tradeService.getExecution("order-123", "BTCUSDT", userId);

        // then
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("getExecution: 여러 건으로 분할 체결되면 수량 가중 평균가/수수료 합/수량 합을 반환한다")
    void getExecution_분할체결_가중평균합산() {
        // given
        Long userId = 1L;
        given(exchangeKeyService.findKey(userId, ExchangeType.BYBIT)).willReturn(makeExchangeKey(userId, "encKey", "encSecret"));
        given(aesEncryptor.decrypt(anyString())).willReturn("raw");

        GetExecutionListResponse response = makeExecutionPage(null,
                makeExec("50000", "0.001", "0.0275", "0.004"),
                makeExec("50100", "0.003", "0.0827", "0.004"));
        given(tradeClient.getExecutionList(anyString(), anyString(), anyString(), anyInt(), any()))
                .willReturn(ResponseEntity.ok(response));

        // when
        GetExecutionListResponse.ExecutionInfo result =
                tradeService.getExecution("order-123", "BTCUSDT", userId);

        // then — (50000×0.001 + 50100×0.003) / 0.004 = 50075
        assertThat(new BigDecimal(result.getExecPrice())).isEqualByComparingTo("50075");
        assertThat(new BigDecimal(result.getExecFee())).isEqualByComparingTo("0.1102");
        assertThat(new BigDecimal(result.getExecQty())).isEqualByComparingTo("0.004");
    }

    @Test
    @DisplayName("getExecution: nextPageCursor가 있으면 다음 페이지까지 조회해 합산한다")
    void getExecution_다음페이지_합산() {
        // given
        Long userId = 1L;
        given(exchangeKeyService.findKey(userId, ExchangeType.BYBIT)).willReturn(makeExchangeKey(userId, "encKey", "encSecret"));
        given(aesEncryptor.decrypt(anyString())).willReturn("raw");

        GetExecutionListResponse page1 = makeExecutionPage("page2",
                makeExec("50000", "0.001", "0.0275", "0.004"));
        GetExecutionListResponse page2 = makeExecutionPage(null,
                makeExec("50100", "0.003", "0.0827", "0.004"));
        given(tradeClient.getExecutionList(anyString(), anyString(), anyString(), anyInt(), isNull()))
                .willReturn(ResponseEntity.ok(page1));
        given(tradeClient.getExecutionList(anyString(), anyString(), anyString(), anyInt(), eq("page2")))
                .willReturn(ResponseEntity.ok(page2));

        // when
        GetExecutionListResponse.ExecutionInfo result =
                tradeService.getExecution("order-123", "BTCUSDT", userId);

        // then
        assertThat(new BigDecimal(result.getExecPrice())).isEqualByComparingTo("50075");
        assertThat(new BigDecimal(result.getExecQty())).isEqualByComparingTo("0.004");
        then(tradeClient).should().getExecutionList(anyString(), anyString(), anyString(), anyInt(), eq("page2"));
    }

    @Test
    @DisplayName("getExecution: 1차에 일부만 반영되면 재시도하여 전량 반영된 결과를 반환한다")
    void getExecution_일부반영_재시도() {
        // given
        Long userId = 1L;
        given(exchangeKeyService.findKey(userId, ExchangeType.BYBIT)).willReturn(makeExchangeKey(userId, "encKey", "encSecret"));
        given(aesEncryptor.decrypt(anyString())).willReturn("raw");

        GetExecutionListResponse partial = makeExecutionPage(null,
                makeExec("50000", "0.001", "0.0275", "0.004"));
        GetExecutionListResponse full = makeExecutionPage(null,
                makeExec("50000", "0.001", "0.0275", "0.004"),
                makeExec("50100", "0.003", "0.0827", "0.004"));
        given(tradeClient.getExecutionList(anyString(), anyString(), anyString(), anyInt(), any()))
                .willReturn(ResponseEntity.ok(partial))
                .willReturn(ResponseEntity.ok(full));

        // when
        GetExecutionListResponse.ExecutionInfo result =
                tradeService.getExecution("order-123", "BTCUSDT", userId);

        // then
        assertThat(new BigDecimal(result.getExecQty())).isEqualByComparingTo("0.004");
        assertThat(new BigDecimal(result.getExecPrice())).isEqualByComparingTo("50075");
    }

    @Test
    @DisplayName("getExecution: 2회 모두 일부만 반영되면 null이 아닌 일부 합산값을 반환한다")
    void getExecution_2회일부반영_부분합산반환() {
        // given
        Long userId = 1L;
        given(exchangeKeyService.findKey(userId, ExchangeType.BYBIT)).willReturn(makeExchangeKey(userId, "encKey", "encSecret"));
        given(aesEncryptor.decrypt(anyString())).willReturn("raw");

        GetExecutionListResponse partial = makeExecutionPage(null,
                makeExec("50000", "0.001", "0.0275", "0.004"));
        given(tradeClient.getExecutionList(anyString(), anyString(), anyString(), anyInt(), any()))
                .willReturn(ResponseEntity.ok(partial));

        // when
        GetExecutionListResponse.ExecutionInfo result =
                tradeService.getExecution("order-123", "BTCUSDT", userId);

        // then
        assertThat(result).isNotNull();
        assertThat(new BigDecimal(result.getExecQty())).isEqualByComparingTo("0.001");
        assertThat(new BigDecimal(result.getExecPrice())).isEqualByComparingTo("50000");
    }

    // ─────────────────────────────────────────────
    // switchToIsolated / setLeverage 캐시 테스트
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("switchToIsolated: 유효 시간 내 재호출이면 Bybit API를 다시 호출하지 않는다")
    void switchToIsolated_캐시_재호출생략() {
        // given
        Long userId = 1L;
        stubApiKey(userId);
        given(clock.millis()).willReturn(0L, 1_000L);
        given(positionClient.setMarginMode(any())).willReturn(ResponseEntity.ok(makeMarginModeResponse("0")));

        // when
        tradeService.switchToIsolated(userId);
        SetMarginModeResponse second = tradeService.switchToIsolated(userId);

        // then
        assertThat(second.getRetCode()).isEqualTo("0");
        then(positionClient).should(times(1)).setMarginMode(any());
    }

    @Test
    @DisplayName("switchToIsolated: 유효 시간이 지나면 Bybit API를 다시 호출한다")
    void switchToIsolated_TTL만료_재호출() {
        // given
        Long userId = 1L;
        stubApiKey(userId);
        given(clock.millis()).willReturn(0L, SETTING_CACHE_TTL_MS + 1);
        given(positionClient.setMarginMode(any())).willReturn(ResponseEntity.ok(makeMarginModeResponse("0")));

        // when
        tradeService.switchToIsolated(userId);
        tradeService.switchToIsolated(userId);

        // then
        then(positionClient).should(times(2)).setMarginMode(any());
    }

    @Test
    @DisplayName("switchToIsolated: 전환 실패 시 캐시하지 않아 다음 호출에서 재시도한다")
    void switchToIsolated_실패시_캐시안함() {
        // given
        Long userId = 1L;
        stubApiKey(userId);
        given(clock.millis()).willReturn(0L, 1_000L);
        given(positionClient.setMarginMode(any())).willReturn(
                ResponseEntity.ok(makeMarginModeResponse("3400045")),
                ResponseEntity.ok(makeMarginModeResponse("0")));

        // when
        assertThatThrownBy(() -> tradeService.switchToIsolated(userId)).isInstanceOf(CustomException.class);
        tradeService.switchToIsolated(userId);

        // then
        then(positionClient).should(times(2)).setMarginMode(any());
    }

    @Test
    @DisplayName("setLeverage: 유효 시간 내 동일 레버리지 재호출이면 Bybit API를 다시 호출하지 않는다")
    void setLeverage_동일값_재호출생략() {
        // given
        Long userId = 1L;
        stubApiKey(userId);
        given(clock.millis()).willReturn(0L, 1_000L);
        given(positionClient.setLeverage(any())).willReturn(ResponseEntity.ok(makeLeverageResponse("110043")));

        // when
        tradeService.setLeverage(makeLeverageRequest("BTCUSDT", "10"), userId);
        SetLeverageResponse second = tradeService.setLeverage(makeLeverageRequest("BTCUSDT", "10"), userId);

        // then
        assertThat(second.getRetCode()).isEqualTo("0");
        then(positionClient).should(times(1)).setLeverage(any());
    }

    @Test
    @DisplayName("setLeverage: 레버리지 값이 바뀌면 Bybit API를 다시 호출한다")
    void setLeverage_값변경_재호출() {
        // given
        Long userId = 1L;
        stubApiKey(userId);
        given(clock.millis()).willReturn(0L, 1_000L);
        given(positionClient.setLeverage(any())).willReturn(ResponseEntity.ok(makeLeverageResponse("0")));

        // when
        tradeService.setLeverage(makeLeverageRequest("BTCUSDT", "10"), userId);
        tradeService.setLeverage(makeLeverageRequest("BTCUSDT", "5"), userId);

        // then
        then(positionClient).should(times(2)).setLeverage(any());
    }

    @Test
    @DisplayName("setLeverage: 심볼이 다르면 각각 Bybit API를 호출한다")
    void setLeverage_심볼별_캐시() {
        // given
        Long userId = 1L;
        stubApiKey(userId);
        given(clock.millis()).willReturn(0L, 1_000L);
        given(positionClient.setLeverage(any())).willReturn(ResponseEntity.ok(makeLeverageResponse("0")));

        // when
        tradeService.setLeverage(makeLeverageRequest("BTCUSDT", "10"), userId);
        tradeService.setLeverage(makeLeverageRequest("ETHUSDT", "10"), userId);

        // then
        then(positionClient).should(times(2)).setLeverage(any());
    }

    @Test
    @DisplayName("setLeverage: 유효 시간이 지나면 Bybit API를 다시 호출한다")
    void setLeverage_TTL만료_재호출() {
        // given
        Long userId = 1L;
        stubApiKey(userId);
        given(clock.millis()).willReturn(0L, SETTING_CACHE_TTL_MS + 1);
        given(positionClient.setLeverage(any())).willReturn(ResponseEntity.ok(makeLeverageResponse("0")));

        // when
        tradeService.setLeverage(makeLeverageRequest("BTCUSDT", "10"), userId);
        tradeService.setLeverage(makeLeverageRequest("BTCUSDT", "10"), userId);

        // then
        then(positionClient).should(times(2)).setLeverage(any());
    }

    @Test
    @DisplayName("setLeverage: 설정 실패 시 캐시하지 않아 다음 호출에서 재시도한다")
    void setLeverage_실패시_캐시안함() {
        // given
        Long userId = 1L;
        stubApiKey(userId);
        given(clock.millis()).willReturn(0L, 1_000L);
        given(positionClient.setLeverage(any())).willReturn(
                ResponseEntity.ok(makeLeverageResponse("10001")),
                ResponseEntity.ok(makeLeverageResponse("0")));

        // when
        assertThatThrownBy(() -> tradeService.setLeverage(makeLeverageRequest("BTCUSDT", "10"), userId))
                .isInstanceOf(CustomException.class);
        tradeService.setLeverage(makeLeverageRequest("BTCUSDT", "10"), userId);

        // then
        then(positionClient).should(times(2)).setLeverage(any());
    }

    // ─────────────────────────────────────────────
    // getClosedPnl 테스트
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("getClosedPnl: 목록에서 청산 주문 ID와 일치하는 건만 반환한다")
    void getClosedPnl_주문ID매칭() {
        // given
        Long userId = 1L;
        ExchangeKeyDTO key = makeExchangeKey(userId, "encKey", "encSecret");
        given(exchangeKeyService.findKey(userId, ExchangeType.BYBIT)).willReturn(key);
        given(aesEncryptor.decrypt(anyString())).willReturn("raw");

        // 다른 큐의 청산 건이 앞에 섞여 있는 상황
        GetClosedPnlResponse response = makeClosedPnlPage(
                makeClosedPnl("other-order", "Sell", "200", "210", "0.11", "0.1155", "9.7745"),
                makeClosedPnl("close-order-1", "Sell", "100", "101", "0.055", "0.0556", "0.8794"));
        given(positionClient.getClosedPnl(anyString(), anyString(), anyInt(), anyLong()))
                .willReturn(ResponseEntity.ok(response));

        // when
        GetClosedPnlResponse.ClosedPnlInfo result = tradeService.getClosedPnl("close-order-1", "BTCUSDT", userId);

        // then
        assertThat(result).isNotNull();
        assertThat(result.getOrderId()).isEqualTo("close-order-1");
        assertThat(new BigDecimal(result.getClosedPnl())).isEqualByComparingTo("0.8794");
        then(positionClient).should(times(1)).getClosedPnl(anyString(), anyString(), eq(50), anyLong());
    }

    @Test
    @DisplayName("getClosedPnl: 2회 모두 일치하는 주문 ID가 없으면 null을 반환한다")
    void getClosedPnl_일치건없음_null반환() {
        // given
        Long userId = 1L;
        ExchangeKeyDTO key = makeExchangeKey(userId, "encKey", "encSecret");
        given(exchangeKeyService.findKey(userId, ExchangeType.BYBIT)).willReturn(key);
        given(aesEncryptor.decrypt(anyString())).willReturn("raw");

        GetClosedPnlResponse response = makeClosedPnlPage(
                makeClosedPnl("other-order", "Sell", "100", "101", "0.055", "0.0556", "0.8794"));
        given(positionClient.getClosedPnl(anyString(), anyString(), anyInt(), anyLong()))
                .willReturn(ResponseEntity.ok(response));

        // when
        GetClosedPnlResponse.ClosedPnlInfo result = tradeService.getClosedPnl("close-order-1", "BTCUSDT", userId);

        // then
        assertThat(result).isNull();
        then(positionClient).should(times(2)).getClosedPnl(anyString(), anyString(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("getClosedPnl: 2회 모두 빈 응답이면 null을 반환한다")
    void getClosedPnl_빈응답_null반환() {
        // given
        Long userId = 1L;
        ExchangeKeyDTO key = makeExchangeKey(userId, "encKey", "encSecret");
        given(exchangeKeyService.findKey(userId, ExchangeType.BYBIT)).willReturn(key);
        given(aesEncryptor.decrypt(anyString())).willReturn("raw");

        GetClosedPnlResponse empty = makeEmptyClosedPnlResponse();
        given(positionClient.getClosedPnl(anyString(), anyString(), anyInt(), anyLong()))
                .willReturn(ResponseEntity.ok(empty));

        // when
        GetClosedPnlResponse.ClosedPnlInfo result = tradeService.getClosedPnl("close-order-1", "BTCUSDT", userId);

        // then
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("getClosedPnl: 롱 청산(side=Sell)이면 펀딩비 = (청산가치-진입가치) - 수수료 - closedPnl 로 역산한다")
    void getClosedPnl_롱청산_펀딩비역산() {
        // given
        Long userId = 1L;
        ExchangeKeyDTO key = makeExchangeKey(userId, "encKey", "encSecret");
        given(exchangeKeyService.findKey(userId, ExchangeType.BYBIT)).willReturn(key);
        given(aesEncryptor.decrypt(anyString())).willReturn("raw");

        // 매매차익 1.0 - 수수료 0.1106 - 펀딩 지급 0.01 = closedPnl 0.8794
        GetClosedPnlResponse response = makeClosedPnlPage(
                makeClosedPnl("close-order-1", "Sell", "100", "101", "0.055", "0.0556", "0.8794"));
        given(positionClient.getClosedPnl(anyString(), anyString(), anyInt(), anyLong()))
                .willReturn(ResponseEntity.ok(response));

        // when
        GetClosedPnlResponse.ClosedPnlInfo result = tradeService.getClosedPnl("close-order-1", "BTCUSDT", userId);

        // then: 양수 = 지급
        assertThat(new BigDecimal(result.getFundingFee())).isEqualByComparingTo("0.01");
    }

    @Test
    @DisplayName("getClosedPnl: 숏 청산(side=Buy)이면 펀딩비 = (진입가치-청산가치) - 수수료 - closedPnl 로 역산한다")
    void getClosedPnl_숏청산_펀딩비역산() {
        // given
        Long userId = 1L;
        ExchangeKeyDTO key = makeExchangeKey(userId, "encKey", "encSecret");
        given(exchangeKeyService.findKey(userId, ExchangeType.BYBIT)).willReturn(key);
        given(aesEncryptor.decrypt(anyString())).willReturn("raw");

        // 매매차익 1.0 - 수수료 0.1106 + 펀딩 수취 0.02 = closedPnl 0.9094
        GetClosedPnlResponse response = makeClosedPnlPage(
                makeClosedPnl("close-order-1", "Buy", "101", "100", "0.0556", "0.055", "0.9094"));
        given(positionClient.getClosedPnl(anyString(), anyString(), anyInt(), anyLong()))
                .willReturn(ResponseEntity.ok(response));

        // when
        GetClosedPnlResponse.ClosedPnlInfo result = tradeService.getClosedPnl("close-order-1", "BTCUSDT", userId);

        // then: 음수 = 수취
        assertThat(new BigDecimal(result.getFundingFee())).isEqualByComparingTo("-0.02");
    }

    @Test
    @DisplayName("getClosedPnl: 역산에 필요한 값이 없으면 펀딩비는 null, closedPnl은 그대로 반환한다")
    void getClosedPnl_역산불가_펀딩비null() {
        // given
        Long userId = 1L;
        ExchangeKeyDTO key = makeExchangeKey(userId, "encKey", "encSecret");
        given(exchangeKeyService.findKey(userId, ExchangeType.BYBIT)).willReturn(key);
        given(aesEncryptor.decrypt(anyString())).willReturn("raw");

        GetClosedPnlResponse response = makeClosedPnlPage(
                makeClosedPnl("close-order-1", "Sell", null, "101", "0.055", "0.0556", "0.8794"));
        given(positionClient.getClosedPnl(anyString(), anyString(), anyInt(), anyLong()))
                .willReturn(ResponseEntity.ok(response));

        // when
        GetClosedPnlResponse.ClosedPnlInfo result = tradeService.getClosedPnl("close-order-1", "BTCUSDT", userId);

        // then
        assertThat(result).isNotNull();
        assertThat(new BigDecimal(result.getClosedPnl())).isEqualByComparingTo("0.8794");
        assertThat(result.getFundingFee()).isNull();
    }

    // ─────────────────────────────────────────────
    // 헬퍼 메서드
    // ─────────────────────────────────────────────

    private GetExecutionListResponse makeExecutionListResponse(String execPrice, String execFee) {
        GetExecutionListResponse.ExecutionInfo info = new GetExecutionListResponse.ExecutionInfo();
        info.setExecPrice(execPrice);
        info.setExecFee(execFee);
        info.setExecQty("0.002");
        info.setOrderQty("0.002");
        info.setSymbol("BTCUSDT");
        info.setOrderId("order-123");

        GetExecutionListResponse.Result result = new GetExecutionListResponse.Result();
        result.setList(List.of(info));

        GetExecutionListResponse response = new GetExecutionListResponse();
        response.setRetCode("0");
        response.setRetMsg("OK");
        response.setResult(result);
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
        GetExecutionListResponse.Result result = new GetExecutionListResponse.Result();
        result.setList(Collections.emptyList());

        GetExecutionListResponse response = new GetExecutionListResponse();
        response.setRetCode("0");
        response.setRetMsg("OK");
        response.setResult(result);
        return response;
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

    private GetClosedPnlResponse makeEmptyClosedPnlResponse() {
        GetClosedPnlResponse.Result result = new GetClosedPnlResponse.Result();
        result.setList(Collections.emptyList());

        GetClosedPnlResponse response = new GetClosedPnlResponse();
        response.setRetCode("0");
        response.setRetMsg("OK");
        response.setResult(result);
        return response;
    }

    private CreateOrderRequest makeOrderRequest(String orderLinkId) {
        return CreateOrderRequest.builder()
                .category("linear")
                .symbol("BTCUSDT")
                .side("Buy")
                .orderType("Market")
                .qty("0.01")
                .orderLinkId(orderLinkId)
                .build();
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

    // ─────────────────────────────────────────────
    // getPositionSize
    // ─────────────────────────────────────────────

    // ─────────────────────────────────────────────
    // findLastClose 테스트 (놓친 외부 청산 재조정)
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("findLastClose: 기준 시각 이후 가장 최근 청산 건을 찾고 체결 내역으로 청산 방식을 판정한다")
    void findLastClose_latestAfterSince_resolvesType() {
        stubApiKey(1L);
        GetClosedPnlResponse.ClosedPnlInfo old = closedAt("old-order", 1000L, "49000");
        GetClosedPnlResponse.ClosedPnlInfo latest = closedAt("close-a", 3000L, "51000");
        GetClosedPnlResponse.ClosedPnlInfo earlier = closedAt("close-b", 2500L, "50500");
        given(positionClient.getClosedPnl(anyString(), eq("BTCUSDT"), anyInt(), anyLong()))
                .willReturn(ResponseEntity.ok(makeClosedPnlPage(earlier, old, latest)));
        GetExecutionListResponse.ExecutionInfo exec = makeExec("51000", "0.001", "0.03", "0.001");
        exec.setExecType("Trade");
        exec.setStopOrderType("TakeProfit");
        exec.setCreateType("CreateByTakeProfit");
        exec.setOrderLinkId("");
        given(tradeClient.getExecutionList(anyString(), eq("BTCUSDT"), eq("close-a"), anyInt(), any()))
                .willReturn(ResponseEntity.ok(makeExecutionPage(null, exec)));

        PositionCloseDTO result = tradeService.findLastClose(1L, "BTCUSDT", 2000L);

        assertThat(result.getOrderId()).isEqualTo("close-a");
        assertThat(result.getExecPrice()).isEqualTo("51000");
        assertThat(result.getCloseType()).isEqualTo(PositionCloseType.TAKE_PROFIT);
        assertThat(result.getUserId()).isEqualTo(1L);
        assertThat(result.getExchangeType()).isEqualTo(ExchangeType.BYBIT);
        assertThat(result.getSymbol()).isEqualTo("BTCUSDT");
    }

    @Test
    @DisplayName("findLastClose: 체결 내역이 없으면 주문 ID는 유지하고 청산 방식은 기타로 판정한다")
    void findLastClose_noExecutions_other() {
        stubApiKey(1L);
        given(positionClient.getClosedPnl(anyString(), anyString(), anyInt(), anyLong()))
                .willReturn(ResponseEntity.ok(makeClosedPnlPage(closedAt("close-a", 3000L, "51000"))));
        given(tradeClient.getExecutionList(anyString(), anyString(), anyString(), anyInt(), any()))
                .willReturn(ResponseEntity.ok(makeEmptyExecutionListResponse()));

        PositionCloseDTO result = tradeService.findLastClose(1L, "BTCUSDT", 2000L);

        assertThat(result.getOrderId()).isEqualTo("close-a");
        assertThat(result.getCloseType()).isEqualTo(PositionCloseType.OTHER);
    }

    @Test
    @DisplayName("findLastClose: 기준 시각 이후 청산 건이 없으면 주문 ID 없이 기타로 반환한다")
    void findLastClose_notFound_otherWithoutOrderId() {
        stubApiKey(1L);
        given(positionClient.getClosedPnl(anyString(), anyString(), anyInt(), anyLong()))
                .willReturn(ResponseEntity.ok(makeClosedPnlPage(closedAt("old-order", 1000L, "49000"))));

        PositionCloseDTO result = tradeService.findLastClose(1L, "BTCUSDT", 2000L);

        assertThat(result.getOrderId()).isNull();
        assertThat(result.getCloseType()).isEqualTo(PositionCloseType.OTHER);
        then(tradeClient).should(never()).getExecutionList(any(), any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("findLastClose: 청산 내역 조회가 에러 응답이거나 예외면 null을 반환한다")
    void findLastClose_errorReturnsNull() {
        stubApiKey(1L);
        GetClosedPnlResponse error = makeEmptyClosedPnlResponse();
        error.setRetCode("10001");
        given(positionClient.getClosedPnl(anyString(), anyString(), anyInt(), anyLong()))
                .willReturn(ResponseEntity.ok(error))
                .willThrow(new RuntimeException("timeout"));

        assertThat(tradeService.findLastClose(1L, "BTCUSDT", 2000L)).isNull();
        assertThat(tradeService.findLastClose(1L, "BTCUSDT", 2000L)).isNull();
    }

    private GetClosedPnlResponse.ClosedPnlInfo closedAt(String orderId, long createdTime, String avgExitPrice) {
        GetClosedPnlResponse.ClosedPnlInfo info = makeClosedPnl(orderId, "Sell", "100", "101", "0.05", "0.05", "0.9");
        info.setCreatedTime(String.valueOf(createdTime));
        info.setAvgExitPrice(avgExitPrice);
        return info;
    }

    @Test
    @DisplayName("getPositionSize: 심볼의 현재 포지션 수량을 반환한다 (포지션 없으면 0)")
    void getPositionSize_returnsSize() {
        stubApiKey(1L);
        given(positionClient.getPositionList("linear", "BTCUSDT"))
                .willReturn(ResponseEntity.ok(makePositionListResponse("0", "0.002")))
                .willReturn(ResponseEntity.ok(makePositionListResponse("0", "0")));

        assertThat(tradeService.getPositionSize(1L, "BTCUSDT")).isEqualByComparingTo("0.002");
        assertThat(tradeService.getPositionSize(1L, "BTCUSDT")).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("getPositionSize: 에러 응답이거나 호출 실패면 null을 반환한다")
    void getPositionSize_errorReturnsNull() {
        stubApiKey(1L);
        given(positionClient.getPositionList("linear", "BTCUSDT"))
                .willReturn(ResponseEntity.ok(makePositionListResponse("10001", "0")))
                .willThrow(new RuntimeException("timeout"));

        assertThat(tradeService.getPositionSize(1L, "BTCUSDT")).isNull();
        assertThat(tradeService.getPositionSize(1L, "BTCUSDT")).isNull();
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
        info.setOrderLinkId("q1-E-1700000000000");
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

    private void stubApiKey(Long userId) {
        given(exchangeKeyService.findKey(userId, ExchangeType.BYBIT))
                .willReturn(makeExchangeKey(userId, "encKey", "encSecret"));
        given(aesEncryptor.decrypt(anyString())).willReturn("raw");
    }

    private SetMarginModeResponse makeMarginModeResponse(String retCode) {
        SetMarginModeResponse response = new SetMarginModeResponse();
        response.setRetCode(retCode);
        response.setRetMsg("0".equals(retCode) ? "OK" : "error");
        return response;
    }

    private SetLeverageResponse makeLeverageResponse(String retCode) {
        SetLeverageResponse response = new SetLeverageResponse();
        response.setRetCode(retCode);
        response.setRetMsg("0".equals(retCode) ? "OK" : "error");
        return response;
    }

    private SetLeverageRequest makeLeverageRequest(String symbol, String leverage) {
        return new SetLeverageRequest("linear", symbol, leverage, leverage);
    }

    private ExchangeKeyDTO makeExchangeKey(Long userId, String encApiKey, String encApiSecret) {
        return ExchangeKeyDTO.builder().userId(userId).exchangeType(ExchangeType.BYBIT)
                .apiKey(encApiKey).apiSecret(encApiSecret).build();
    }
}
