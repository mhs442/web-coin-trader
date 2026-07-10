package com.coin.webcointrader.trade.service;

import com.coin.webcointrader.autotrade.repository.TradeHistoryRepository;
import com.coin.webcointrader.common.client.position.PositionClient;
import com.coin.webcointrader.common.client.trade.TradeClient;
import com.coin.webcointrader.common.dto.request.CreateOrderRequest;
import com.coin.webcointrader.common.dto.response.CreateOrderResponse;
import com.coin.webcointrader.common.dto.response.GetClosedPnlResponse;
import com.coin.webcointrader.common.dto.response.GetExecutionListResponse;
import com.coin.webcointrader.common.entity.UserExchangeKey;
import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.repository.UserExchangeKeyRepository;
import com.coin.webcointrader.common.util.AesEncryptor;
import com.coin.webcointrader.common.util.UserApiKeyContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class TradeServiceTest {

    @InjectMocks
    private TradeService tradeService;

    @Mock
    private TradeClient tradeClient;

    @Mock
    private PositionClient positionClient;

    @Mock
    private TradeHistoryRepository tradeHistoryRepository;

    @Mock
    private UserExchangeKeyRepository userExchangeKeyRepository;

    @Mock
    private AesEncryptor aesEncryptor;

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

        UserExchangeKey key = makeExchangeKey(userId, "encKey", "encSecret");
        CreateOrderResponse response = new CreateOrderResponse();
        response.setRetCode("0");
        response.setRetMsg("OK");

        given(userExchangeKeyRepository.findByUserIdAndExchangeType(userId, ExchangeType.BYBIT)).willReturn(Optional.of(key));
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

        UserExchangeKey key = makeExchangeKey(userId, "encKey", "encSecret");
        CreateOrderResponse response = new CreateOrderResponse();
        response.setRetCode("0");
        response.setRetMsg("OK");

        given(userExchangeKeyRepository.findByUserIdAndExchangeType(userId, ExchangeType.BYBIT)).willReturn(Optional.of(key));
        given(aesEncryptor.decrypt(anyString())).willReturn("raw");
        given(tradeClient.createOrder(any(CreateOrderRequest.class))).willReturn(ResponseEntity.ok(response));

        // when
        tradeService.placeOrder(request, userId);

        // then - category가 "linear"로 설정된 request로 호출됨
        then(tradeClient).should().createOrder(argThat(r -> "linear".equals(r.getCategory())));
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

        given(userExchangeKeyRepository.findByUserIdAndExchangeType(userId, ExchangeType.BYBIT)).willReturn(Optional.empty());

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

        UserExchangeKey key = makeExchangeKey(userId, "encKey", "encSecret");
        given(userExchangeKeyRepository.findByUserIdAndExchangeType(userId, ExchangeType.BYBIT)).willReturn(Optional.of(key));
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
    // getExecution 테스트
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("getExecution: 정상 응답이면 실체결 정보를 반환한다")
    void getExecution_성공_실체결데이터반환() {
        // given
        Long userId = 1L;
        UserExchangeKey key = makeExchangeKey(userId, "encKey", "encSecret");
        given(userExchangeKeyRepository.findByUserIdAndExchangeType(userId, ExchangeType.BYBIT)).willReturn(Optional.of(key));
        given(aesEncryptor.decrypt(anyString())).willReturn("raw");

        GetExecutionListResponse response = makeExecutionListResponse("50100.50", "0.0551");
        given(tradeClient.getExecutionList(anyString(), anyString(), anyString(), anyInt()))
                .willReturn(ResponseEntity.ok(response));

        // when
        GetExecutionListResponse.ExecutionInfo result =
                tradeService.getExecution("order-123", "BTCUSDT", userId);

        // then
        assertThat(result).isNotNull();
        assertThat(result.getExecPrice()).isEqualTo("50100.50");
        assertThat(result.getExecFee()).isEqualTo("0.0551");
    }

    @Test
    @DisplayName("getExecution: 1차 빈 응답 후 2차 성공 시 실체결 정보를 반환한다")
    void getExecution_1차빈응답_2차성공시반환() {
        // given
        Long userId = 1L;
        UserExchangeKey key = makeExchangeKey(userId, "encKey", "encSecret");
        given(userExchangeKeyRepository.findByUserIdAndExchangeType(userId, ExchangeType.BYBIT)).willReturn(Optional.of(key));
        given(aesEncryptor.decrypt(anyString())).willReturn("raw");

        GetExecutionListResponse empty = makeEmptyExecutionListResponse();
        GetExecutionListResponse success = makeExecutionListResponse("50200.00", "0.0552");
        given(tradeClient.getExecutionList(anyString(), anyString(), anyString(), anyInt()))
                .willReturn(ResponseEntity.ok(empty))
                .willReturn(ResponseEntity.ok(success));

        // when
        GetExecutionListResponse.ExecutionInfo result =
                tradeService.getExecution("order-123", "BTCUSDT", userId);

        // then
        assertThat(result).isNotNull();
        assertThat(result.getExecPrice()).isEqualTo("50200.00");
    }

    @Test
    @DisplayName("getExecution: 2회 모두 빈 응답이면 null을 반환한다")
    void getExecution_2회빈응답_null반환() {
        // given
        Long userId = 1L;
        UserExchangeKey key = makeExchangeKey(userId, "encKey", "encSecret");
        given(userExchangeKeyRepository.findByUserIdAndExchangeType(userId, ExchangeType.BYBIT)).willReturn(Optional.of(key));
        given(aesEncryptor.decrypt(anyString())).willReturn("raw");

        GetExecutionListResponse empty = makeEmptyExecutionListResponse();
        given(tradeClient.getExecutionList(anyString(), anyString(), anyString(), anyInt()))
                .willReturn(ResponseEntity.ok(empty));

        // when
        GetExecutionListResponse.ExecutionInfo result =
                tradeService.getExecution("order-123", "BTCUSDT", userId);

        // then
        assertThat(result).isNull();
    }

    // ─────────────────────────────────────────────
    // getClosedPnl 테스트
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("getClosedPnl: 정상 응답이면 ClosedPnlInfo(closedPnl + fundingFee)를 반환한다")
    void getClosedPnl_성공_실손익반환() {
        // given
        Long userId = 1L;
        UserExchangeKey key = makeExchangeKey(userId, "encKey", "encSecret");
        given(userExchangeKeyRepository.findByUserIdAndExchangeType(userId, ExchangeType.BYBIT)).willReturn(Optional.of(key));
        given(aesEncryptor.decrypt(anyString())).willReturn("raw");

        GetClosedPnlResponse response = makeClosedPnlResponse("12.5000", "-0.0125");
        given(positionClient.getClosedPnl(anyString(), anyString(), anyInt(), anyLong()))
                .willReturn(ResponseEntity.ok(response));

        // when
        GetClosedPnlResponse.ClosedPnlInfo result = tradeService.getClosedPnl("BTCUSDT", userId);

        // then
        assertThat(result).isNotNull();
        assertThat(new BigDecimal(result.getClosedPnl())).isEqualByComparingTo(new BigDecimal("12.5000"));
        assertThat(new BigDecimal(result.getFundingFee())).isEqualByComparingTo(new BigDecimal("-0.0125"));
    }

    @Test
    @DisplayName("getClosedPnl: 2회 모두 빈 응답이면 null을 반환한다")
    void getClosedPnl_빈응답_null반환() {
        // given
        Long userId = 1L;
        UserExchangeKey key = makeExchangeKey(userId, "encKey", "encSecret");
        given(userExchangeKeyRepository.findByUserIdAndExchangeType(userId, ExchangeType.BYBIT)).willReturn(Optional.of(key));
        given(aesEncryptor.decrypt(anyString())).willReturn("raw");

        GetClosedPnlResponse empty = makeEmptyClosedPnlResponse();
        given(positionClient.getClosedPnl(anyString(), anyString(), anyInt(), anyLong()))
                .willReturn(ResponseEntity.ok(empty));

        // when
        GetClosedPnlResponse.ClosedPnlInfo result = tradeService.getClosedPnl("BTCUSDT", userId);

        // then
        assertThat(result).isNull();
    }

    // ─────────────────────────────────────────────
    // 헬퍼 메서드
    // ─────────────────────────────────────────────

    private GetExecutionListResponse makeExecutionListResponse(String execPrice, String execFee) {
        GetExecutionListResponse.ExecutionInfo info = new GetExecutionListResponse.ExecutionInfo();
        info.setExecPrice(execPrice);
        info.setExecFee(execFee);
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

    private GetExecutionListResponse makeEmptyExecutionListResponse() {
        GetExecutionListResponse.Result result = new GetExecutionListResponse.Result();
        result.setList(Collections.emptyList());

        GetExecutionListResponse response = new GetExecutionListResponse();
        response.setRetCode("0");
        response.setRetMsg("OK");
        response.setResult(result);
        return response;
    }

    private GetClosedPnlResponse makeClosedPnlResponse(String closedPnl, String fundingFee) {
        GetClosedPnlResponse.ClosedPnlInfo info = new GetClosedPnlResponse.ClosedPnlInfo();
        info.setClosedPnl(closedPnl);
        info.setFundingFee(fundingFee);
        info.setSymbol("BTCUSDT");

        GetClosedPnlResponse.Result result = new GetClosedPnlResponse.Result();
        result.setList(List.of(info));

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

    private UserExchangeKey makeExchangeKey(Long userId, String encApiKey, String encApiSecret) {
        UserExchangeKey key = new UserExchangeKey();
        key.setUserId(userId);
        key.setExchangeType(ExchangeType.BYBIT);
        key.setApiKey(encApiKey);
        key.setApiSecret(encApiSecret);
        return key;
    }
}
