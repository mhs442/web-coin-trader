package com.coin.webcointrader.login.service;

import com.coin.webcointrader.common.dto.UserDTO;
import com.coin.webcointrader.common.entity.User;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.login.repository.LoginRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class LoginServiceTest {

    @InjectMocks
    private LoginService loginService;

    @Mock
    private LoginRepository loginRepository;

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
        user.setApiKey("encryptedKey");
        user.setApiSecret("encryptedSecret");

        given(loginRepository.findByPhoneNumber("01012345678"))
                .willReturn(Optional.of(user));

        // when
        UserDTO result = loginService.loadUserByUsername("01012345678");

        // then
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getPhoneNumber()).isEqualTo("01012345678");
        assertThat(result.getUsername()).isEqualTo("테스터");
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
}
