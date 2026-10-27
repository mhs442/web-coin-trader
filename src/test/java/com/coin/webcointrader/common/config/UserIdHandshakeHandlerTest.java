package com.coin.webcointrader.common.config;

import com.coin.webcointrader.common.login.dto.UserDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.security.Principal;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * WebSocket 연결 사용자 식별 단위 테스트.
 * 로그인 세션의 사용자 PK가 STOMP 사용자 전용 채널의 식별값으로 지정되는지 검증한다.
 */
class UserIdHandshakeHandlerTest {

    private final UserIdHandshakeHandler handler = new UserIdHandshakeHandler();

    @Test
    @DisplayName("로그인 사용자: 사용자 PK(userId)를 연결 식별값으로 지정한다 (전화번호 아님)")
    void loggedInUser_userIdAsPrincipalName() {
        UserDTO user = UserDTO.builder().id(7L).phoneNumber("01012345678").username("tester").password("pw").build();
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        given(request.getPrincipal())
                .willReturn(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));

        Principal principal = handler.determineUser(request, null, new HashMap<>());

        assertThat(principal).isNotNull();
        assertThat(principal.getName()).isEqualTo("7");
    }

    @Test
    @DisplayName("비로그인 연결: 식별값 없음 (가격 등 공개 채널만 수신)")
    void anonymous_noPrincipal() {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        given(request.getPrincipal()).willReturn(null);

        assertThat(handler.determineUser(request, null, new HashMap<>())).isNull();
    }

    @Test
    @DisplayName("UserDTO가 아닌 인증 정보: 식별값 없음")
    void unknownPrincipal_noPrincipal() {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        given(request.getPrincipal()).willReturn(new UsernamePasswordAuthenticationToken("someone", null));

        assertThat(handler.determineUser(request, null, new HashMap<>())).isNull();
    }
}
