package com.coin.webcointrader.common.config;

import com.coin.webcointrader.common.login.dto.UserDTO;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Map;

/**
 * WebSocket 연결 사용자 식별 핸들러.
 *
 * <p>연결 시점의 로그인 세션에서 사용자 PK(userId)를 꺼내 연결 식별값으로 지정한다.
 * 서버는 {@code convertAndSendToUser(userId, ...)}로 해당 사용자의 연결에만 메시지를 보낸다.
 * (기본값인 로그인 이름은 전화번호라, 자동매매 엔진이 가진 userId로 바로 보낼 수 있도록 교체)</p>
 *
 * <p>식별값은 브라우저가 보낸 값이 아닌 서버 세션에서 꺼내므로 다른 사용자로 위장할 수 없다.
 * 비로그인 연결은 식별값이 없어 사용자 전용 채널을 받지 못하고, 가격 등 공개 채널만 수신한다.</p>
 */
public class UserIdHandshakeHandler extends DefaultHandshakeHandler {

    @Override
    protected Principal determineUser(ServerHttpRequest request, WebSocketHandler wsHandler,
                                      Map<String, Object> attributes) {
        // 로그인 사용자(UserDTO)만 userId로 식별, 그 외(비로그인·알 수 없는 인증)는 식별값 없음
        if (request.getPrincipal() instanceof Authentication auth
                && auth.getPrincipal() instanceof UserDTO user) {
            String userId = String.valueOf(user.getId());
            return () -> userId;
        }
        return null;
    }
}
