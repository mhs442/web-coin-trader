package com.coin.webcointrader.login.repository;

import com.coin.webcointrader.common.config.JpaConfig;
import com.coin.webcointrader.common.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

@DataJpaTest
@Import(JpaConfig.class)
class LoginRepositoryTest {

    @Autowired
    private LoginRepository loginRepository;

    @AfterEach
    void tearDown() {
        loginRepository.deleteAll();
    }

    // ─────────────────────────────────────────────
    // findByPhoneNumber
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("findByPhoneNumber: 등록된 전화번호로 조회하면 User를 반환한다")
    void findByPhoneNumber_returnsUser() {
        // given
        User user = makeUser("tester", "01012345678", "test@test.com");
        loginRepository.save(user);

        // when
        Optional<User> result = loginRepository.findByPhoneNumber("01012345678");

        // then
        assertThat(result).isPresent();
        assertThat(result.get().getPhoneNumber()).isEqualTo("01012345678");
        assertThat(result.get().getUsername()).isEqualTo("tester");
    }

    @Test
    @DisplayName("findByPhoneNumber: 등록되지 않은 전화번호로 조회하면 empty를 반환한다")
    void findByPhoneNumber_returnsEmpty() {
        // when
        Optional<User> result = loginRepository.findByPhoneNumber("01099999999");

        // then
        assertThat(result).isEmpty();
    }

    // ─────────────────────────────────────────────
    // 헬퍼 메서드
    // ─────────────────────────────────────────────

    private User makeUser(String username, String phoneNumber, String email) {
        User user = new User();
        user.setUsername(username);
        user.setPhoneNumber(phoneNumber);
        user.setEmail(email);
        user.setPassword("encodedPassword");
        user.setApiKey("encApiKey");
        user.setApiSecret("encApiSecret");
        return user;
    }
}
