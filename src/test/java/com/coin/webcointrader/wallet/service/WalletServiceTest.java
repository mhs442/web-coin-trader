package com.coin.webcointrader.wallet.service;

import com.coin.webcointrader.common.client.account.AccountClient;
import com.coin.webcointrader.common.dto.response.GetWalletBalanceResponse;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    @InjectMocks
    private WalletService walletService;

    @Mock
    private AccountClient accountClient;

    @Mock
    private LoginRepository loginRepository;

    @Mock
    private AesEncryptor aesEncryptor;

    @AfterEach
    void clearContext() {
        UserApiKeyContext.clear();
    }

    @Test
    @DisplayName("getWalletBalance: retCode가 0이면 지갑 잔고 응답을 반환한다")
    void getWalletBalance_success() {
        // given
        Long userId = 1L;
        User user = makeUser(userId, "encKey", "encSecret");
        GetWalletBalanceResponse response = new GetWalletBalanceResponse();
        response.setRetCode("0");
        response.setRetMsg("OK");

        given(loginRepository.findById(userId)).willReturn(Optional.of(user));
        given(aesEncryptor.decrypt(anyString())).willReturn("raw");
        given(accountClient.getWalletBalance("UNIFIED")).willReturn(ResponseEntity.ok(response));

        // when
        GetWalletBalanceResponse result = walletService.getWalletBalance(userId);

        // then
        assertThat(result).isNotNull();
        assertThat(result.getRetCode()).isEqualTo("0");
    }

    @Test
    @DisplayName("getWalletBalance: 사용자를 찾을 수 없으면 CustomException(USER_NOT_FOUND) 발생")
    void getWalletBalance_userNotFound() {
        // given
        Long userId = 99L;
        given(loginRepository.findById(userId)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> walletService.getWalletBalance(userId))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining(ExceptionMessage.USER_NOT_FOUND.getMessage());
    }

    @Test
    @DisplayName("getWalletBalance: Bybit 응답 retCode가 0이 아니면 CustomException(GET_WALLET_BALANCE_FAILED) 발생")
    void getWalletBalance_retCodeFailure_throwsException() {
        // given
        Long userId = 1L;
        User user = makeUser(userId, "encKey", "encSecret");
        GetWalletBalanceResponse response = new GetWalletBalanceResponse();
        response.setRetCode("10003"); // Bybit 서명/권한 오류 등
        response.setRetMsg("Invalid API key");

        given(loginRepository.findById(userId)).willReturn(Optional.of(user));
        given(aesEncryptor.decrypt(anyString())).willReturn("raw");
        given(accountClient.getWalletBalance("UNIFIED")).willReturn(ResponseEntity.ok(response));

        // when & then
        assertThatThrownBy(() -> walletService.getWalletBalance(userId))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining(ExceptionMessage.GET_WALLET_BALANCE_FAILED.getMessage())
                .hasMessageContaining("Invalid API key");
    }

    @Test
    @DisplayName("getWalletBalance: 응답 본문이 null이면 CustomException(GET_WALLET_BALANCE_FAILED) 발생")
    void getWalletBalance_nullResponse_throwsException() {
        // given
        Long userId = 1L;
        User user = makeUser(userId, "encKey", "encSecret");

        given(loginRepository.findById(userId)).willReturn(Optional.of(user));
        given(aesEncryptor.decrypt(anyString())).willReturn("raw");
        given(accountClient.getWalletBalance("UNIFIED")).willReturn(ResponseEntity.ok(null));

        // when & then
        assertThatThrownBy(() -> walletService.getWalletBalance(userId))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining(ExceptionMessage.GET_WALLET_BALANCE_FAILED.getMessage());
    }

    @Test
    @DisplayName("getWalletBalance: 처리 후 UserApiKeyContext가 정리된다")
    void getWalletBalance_clearsContext() {
        // given
        Long userId = 1L;
        User user = makeUser(userId, "encKey", "encSecret");
        GetWalletBalanceResponse response = new GetWalletBalanceResponse();
        response.setRetCode("0");

        given(loginRepository.findById(userId)).willReturn(Optional.of(user));
        given(aesEncryptor.decrypt(anyString())).willReturn("raw");
        given(accountClient.getWalletBalance("UNIFIED")).willReturn(ResponseEntity.ok(response));

        // when
        walletService.getWalletBalance(userId);

        // then
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
