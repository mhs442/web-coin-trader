package com.coin.webcointrader.common.login.service;

import com.coin.webcointrader.common.login.dto.UserDTO;
import com.coin.webcointrader.common.login.entity.User;
import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.login.dto.SignupRequest;
import com.coin.webcointrader.common.login.repository.LoginRepository;
import com.coin.webcointrader.common.sim.service.SimWalletService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class LoginServiceTest {

    @InjectMocks
    private LoginService loginService;

    @Mock
    private LoginRepository loginRepository;

    @Mock
    private BCryptPasswordEncoder passwordEncoder;

    @Mock
    private ExchangeKeyRegisterService exchangeKeyRegisterService;

    @Mock
    private SimWalletService simWalletService;

    @Test
    @DisplayName("loadUserByUsername: 전화번호로 사용자를 찾으면 UserDTO를 반환한다")
    void loadUserByUsername_success() {
        // given
        User user = new User();
        user.setId(1L);
        user.setPhoneNumber("01012345678");
        user.setUsername("테스터");
        user.setPassword("encodedPassword");
        user.setEmail("test@test.com");

        given(loginRepository.findByPhoneNumber("01012345678"))
                .willReturn(Optional.of(user));

        // when
        UserDTO result = loginService.loadUserByUsername("01012345678");

        // then
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getPhoneNumber()).isEqualTo("01012345678");
        assertThat(result.getPassword()).isEqualTo("encodedPassword");
    }

    @Test
    @DisplayName("loadUserByUsername: 존재하지 않는 전화번호면 CustomException 발생")
    void loadUserByUsername_notFound() {
        // given
        given(loginRepository.findByPhoneNumber("00000000000"))
                .willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> loginService.loadUserByUsername("00000000000"))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("사용자를 찾을 수 없습니다");
    }

    @Test
    @DisplayName("signup: 유효한 요청이면 사용자 저장 후 키 등록을 공통 서비스에 위임한다")
    void signup_success() {
        // given
        SignupRequest request = makeRequest("tester", "01012345678",
                "password1!", "password1!", "apiKey", "apiSecret");

        given(loginRepository.findByPhoneNumber("01012345678")).willReturn(Optional.empty());
        given(passwordEncoder.encode("password1!")).willReturn("encodedPassword");
        given(loginRepository.save(any(User.class))).willAnswer(inv -> {
            User saved = inv.getArgument(0);
            saved.setId(1L);
            return saved;
        });

        // when & then
        assertThatCode(() -> loginService.signup(request))
                .doesNotThrowAnyException();

        then(loginRepository).should().save(any(User.class));
        // 키 검증·암호화·저장은 ExchangeKeyRegisterService가 담당
        then(exchangeKeyRegisterService).should().saveKey(1L, ExchangeType.BYBIT, "apiKey", "apiSecret");
        // 가입 시 모의 지갑 생성은 sim 도메인 서비스에 위임
        then(simWalletService).should().createWallet(1L);
    }

    @Test
    @DisplayName("signup: 비밀번호가 일치하지 않으면 CustomException(PASSWORD_MISMATCH) 발생")
    void signup_passwordMismatch() {
        // given
        SignupRequest request = makeRequest("tester", "01012345678",
                "password1!", "different!", "apiKey", "apiSecret");

        // when & then
        assertThatThrownBy(() -> loginService.signup(request))
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
        assertThatThrownBy(() -> loginService.signup(request))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining(ExceptionMessage.DUPLICATE_PHONE_NUMBER.getMessage());
    }

    @Test
    @DisplayName("signup: 키 등록에서 INVALID_API_KEY가 나면 그대로 전파하고 지갑을 만들지 않는다 (트랜잭션 롤백)")
    void signup_invalidApiKey() {
        // given
        SignupRequest request = makeRequest("tester", "01012345678",
                "password1!", "password1!", "invalidKey", "invalidSecret");

        given(loginRepository.findByPhoneNumber("01012345678")).willReturn(Optional.empty());
        given(loginRepository.save(any(User.class))).willAnswer(inv -> {
            User saved = inv.getArgument(0);
            saved.setId(1L);
            return saved;
        });
        willThrow(new CustomException(ExceptionMessage.INVALID_API_KEY))
                .given(exchangeKeyRegisterService).saveKey(1L, ExchangeType.BYBIT, "invalidKey", "invalidSecret");

        // when & then
        assertThatThrownBy(() -> loginService.signup(request))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining(ExceptionMessage.INVALID_API_KEY.getMessage());
        then(simWalletService).should(never()).createWallet(any());
    }

    @Test
    @DisplayName("signup: 지원하지 않는 거래소면 사용자·키 저장 없이 CustomException(INVALID_EXCHANGE_TYPE) 발생")
    void signup_invalidExchangeType() {
        SignupRequest request = makeRequest("tester", "01012345678",
                "password1!", "password1!", "apiKey", "apiSecret", "UNKNOWN");
        given(loginRepository.findByPhoneNumber("01012345678")).willReturn(Optional.empty());

        assertThatThrownBy(() -> loginService.signup(request))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining(ExceptionMessage.INVALID_EXCHANGE_TYPE.getMessage());
        then(exchangeKeyRegisterService).shouldHaveNoInteractions();
        then(loginRepository).should(never()).save(any(User.class));
    }

    @Test
    @DisplayName("signup: 거래소 타입은 대소문자를 구분하지 않는다")
    void signup_exchangeTypeCaseInsensitive() {
        SignupRequest request = makeRequest("tester", "01012345678",
                "password1!", "password1!", "apiKey", "apiSecret", "bybit");
        given(loginRepository.findByPhoneNumber("01012345678")).willReturn(Optional.empty());
        given(loginRepository.save(any(User.class))).willAnswer(inv -> {
            User saved = inv.getArgument(0);
            saved.setId(1L);
            return saved;
        });

        loginService.signup(request);

        then(exchangeKeyRegisterService).should().saveKey(1L, ExchangeType.BYBIT, "apiKey", "apiSecret");
    }

    // ─────────────────────────────────────────────
    // 헬퍼 메서드
    // ─────────────────────────────────────────────

    private SignupRequest makeRequest(String username, String phoneNumber,
                                      String password, String passwordConfirm,
                                      String apiKey, String apiSecret) {
        return makeRequest(username, phoneNumber, password, passwordConfirm, apiKey, apiSecret, "BYBIT");
    }

    private SignupRequest makeRequest(String username, String phoneNumber,
                                      String password, String passwordConfirm,
                                      String apiKey, String apiSecret, String exchangeType) {
        SignupRequest req = new SignupRequest();
        req.setUsername(username);
        req.setPhoneNumber(phoneNumber);
        req.setPassword(password);
        req.setPasswordConfirm(passwordConfirm);
        req.setApiKey(apiKey);
        req.setApiSecret(apiSecret);
        req.setExchangeType(exchangeType);
        return req;
    }
}
