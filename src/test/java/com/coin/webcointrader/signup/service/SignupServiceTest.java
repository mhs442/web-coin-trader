package com.coin.webcointrader.signup.service;

import com.coin.webcointrader.common.entity.User;
import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.login.repository.LoginRepository;
import com.coin.webcointrader.signup.dto.SignupRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class SignupServiceTest {

    @InjectMocks
    private SignupService signupService;

    @Mock
    private LoginRepository loginRepository;

    @Mock
    private BCryptPasswordEncoder passwordEncoder;

    @Mock
    private BybitApiKeyValidator bybitApiKeyValidator;

    @Mock
    private com.coin.webcointrader.common.util.AesEncryptor aesEncryptor;

    @Test
    @DisplayName("signup: 유효한 요청이면 사용자를 저장한다")
    void signup_success() {
        // given
        SignupRequest request = makeRequest("tester", "01012345678",
                "password1!", "password1!", "apiKey", "apiSecret");

        given(loginRepository.findByPhoneNumber("01012345678")).willReturn(Optional.empty());
        given(bybitApiKeyValidator.validate("apiKey", "apiSecret")).willReturn(true);
        given(passwordEncoder.encode("password1!")).willReturn("encodedPassword");
        given(aesEncryptor.encrypt("apiKey")).willReturn("encApiKey");
        given(aesEncryptor.encrypt("apiSecret")).willReturn("encApiSecret");
        given(loginRepository.save(any(User.class))).willAnswer(inv -> inv.getArgument(0));

        // when & then
        assertThatCode(() -> signupService.signup(request))
                .doesNotThrowAnyException();

        then(loginRepository).should().save(any(User.class));
    }

    @Test
    @DisplayName("signup: 비밀번호가 일치하지 않으면 CustomException(PASSWORD_MISMATCH) 발생")
    void signup_passwordMismatch() {
        // given
        SignupRequest request = makeRequest("tester", "01012345678",
                "password1!", "different!", "apiKey", "apiSecret");

        // when & then
        assertThatThrownBy(() -> signupService.signup(request))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining(ExceptionMessage.PASSWORD_MISMATCH.getMessage());
    }

    @Test
    @DisplayName("signup: 이미 등록된 전화번호면 CustomException(DUPLICATE_PHONE_NUMBER) 발생")
    void signup_duplicatePhoneNumber() {
        // given
        SignupRequest request = makeRequest("tester", "01012345678",
                "password1!", "password1!", "apiKey", "apiSecret");

        User existing = new User();
        given(loginRepository.findByPhoneNumber("01012345678")).willReturn(Optional.of(existing));

        // when & then
        assertThatThrownBy(() -> signupService.signup(request))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining(ExceptionMessage.DUPLICATE_PHONE_NUMBER.getMessage());
    }

    @Test
    @DisplayName("signup: Bybit API Key가 유효하지 않으면 CustomException(INVALID_API_KEY) 발생")
    void signup_invalidApiKey() {
        // given
        SignupRequest request = makeRequest("tester", "01012345678",
                "password1!", "password1!", "invalidKey", "invalidSecret");

        given(loginRepository.findByPhoneNumber("01012345678")).willReturn(Optional.empty());
        given(bybitApiKeyValidator.validate("invalidKey", "invalidSecret")).willReturn(false);

        // when & then
        assertThatThrownBy(() -> signupService.signup(request))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining(ExceptionMessage.INVALID_API_KEY.getMessage());
    }

    // ─────────────────────────────────────────────
    // 헬퍼 메서드
    // ─────────────────────────────────────────────

    private SignupRequest makeRequest(String username, String phoneNumber,
                                      String password, String passwordConfirm,
                                      String apiKey, String apiSecret) {
        SignupRequest req = new SignupRequest();
        req.setUsername(username);
        req.setPhoneNumber(phoneNumber);
        req.setPassword(password);
        req.setPasswordConfirm(passwordConfirm);
        req.setApiKey(apiKey);
        req.setApiSecret(apiSecret);
        return req;
    }
}
