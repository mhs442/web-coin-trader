package com.coin.webcointrader.trade.service;

import com.coin.webcointrader.client.trade.TradeClient;
import com.coin.webcointrader.common.dto.request.CreateOrderRequest;
import com.coin.webcointrader.common.dto.response.CreateOrderResponse;
import com.coin.webcointrader.common.entity.User;
import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.util.AesEncryptor;
import com.coin.webcointrader.common.util.UserApiKeyContext;
import com.coin.webcointrader.login.repository.LoginRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class TradeServiceTest {

    @InjectMocks
    private TradeService tradeService;

    @Mock
    private TradeClient tradeClient;

    @Mock
    private LoginRepository loginRepository;

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

        User user = makeUser(userId, "encKey", "encSecret");
        CreateOrderResponse response = new CreateOrderResponse();

        given(loginRepository.findById(userId)).willReturn(Optional.of(user));
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

        User user = makeUser(userId, "encKey", "encSecret");
        CreateOrderResponse response = new CreateOrderResponse();

        given(loginRepository.findById(userId)).willReturn(Optional.of(user));
        given(aesEncryptor.decrypt(anyString())).willReturn("raw");
        given(tradeClient.createOrder(any(CreateOrderRequest.class))).willReturn(ResponseEntity.ok(response));

        // when
        tradeService.placeOrder(request, userId);

        // then - category가 "linear"로 설정된 request로 호출됨
        then(tradeClient).should().createOrder(argThat(r -> "linear".equals(r.getCategory())));
    }

    @Test
    @DisplayName("placeOrder: 사용자를 찾을 수 없으면 CustomException(USER_NOT_FOUND) 발생")
    void placeOrder_userNotFound() {
        // given
        Long userId = 99L;
        CreateOrderRequest request = CreateOrderRequest.builder()
                .category("linear")
                .symbol("BTCUSDT")
                .side("Buy")
                .orderType("Market")
                .qty("0.01")
                .build();

        given(loginRepository.findById(userId)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> tradeService.placeOrder(request, userId))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining(ExceptionMessage.USER_NOT_FOUND.getMessage());
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

        User user = makeUser(userId, "encKey", "encSecret");
        given(loginRepository.findById(userId)).willReturn(Optional.of(user));
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
    // 헬퍼 메서드
    // ─────────────────────────────────────────────

    private User makeUser(Long id, String encApiKey, String encApiSecret) {
        User user = new User();
        user.setId(id);
        user.setPhoneNumber("01012345678");
        user.setUsername("tester");
        user.setEmail("test@test.com");
        user.setPassword("encodedPw");
        user.setApiKey(encApiKey);
        user.setApiSecret(encApiSecret);
        return user;
    }
}
