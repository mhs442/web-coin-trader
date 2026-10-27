package com.coin.webcointrader.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP WebSocket 메시지 브로커 설정.
 *
 * <p>서버 → 브라우저 실시간 데이터 push를 위한 STOMP 프로토콜 설정이다.
 * Spring의 내장 심플 브로커(in-memory)를 사용하며,
 * 외부 메시지 브로커(RabbitMQ 등)는 사용하지 않는다.</p>
 *
 * <h3>토픽 구조:</h3>
 * <ul>
 *   <li>{@code /topic/price.{exchange}.{symbol}} - 실시간 가격 (공개, 예: /topic/price.BYBIT.BTCUSDT)</li>
 *   <li>{@code /user/queue/autotrade.status.{exchange}.{mode}.{symbol}} - 자동매매 상태 (본인 전용)</li>
 *   <li>{@code /user/queue/autotrade.alert.{exchange}.{mode}.{symbol}} - 큐 비활성화 등 즉시 알림 (본인 전용)</li>
 * </ul>
 *
 * <p>본인 전용 채널은 연결 시 {@link UserIdHandshakeHandler}가 지정한 userId로 구분되며,
 * 서버는 {@code convertAndSendToUser(userId, "/queue/...", payload)}로 전송한다.</p>
 *
 * <h3>엔드포인트:</h3>
 * <ul>
 *   <li>{@code /ws} - 브라우저에서 SockJS로 연결하는 WebSocket 엔드포인트</li>
 * </ul>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketStompConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * 메시지 브로커를 설정한다.
     *
     * <p>{@code /topic} prefix로 시작하는 목적지(destination)에 대해
     * 심플 브로커가 메시지를 브라우저 구독자에게 전달한다.</p>
     *
     * @param registry 메시지 브로커 설정 레지스트리
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // /topic: 공개 채널 (구독한 모든 브라우저에 전달)
        // /queue: 사용자 전용 채널 (브라우저는 /user/queue/... 로 구독, 기본 user prefix "/user")
        registry.enableSimpleBroker("/topic", "/queue");
    }

    /**
     * STOMP 연결 엔드포인트를 등록한다.
     *
     * <p>브라우저에서 {@code new SockJS('/ws')}로 연결하면,
     * 내부적으로 WebSocket → HTTP Streaming → HTTP Long Polling 순으로 fallback한다.</p>
     *
     * @param registry STOMP 엔드포인트 레지스트리
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // /ws 경로로 WebSocket 연결을 받는다
        // setHandshakeHandler: 로그인 사용자 연결에 userId 식별값 지정 (본인 전용 채널용)
        // withSockJS(): WebSocket을 지원하지 않는 브라우저를 위한 SockJS fallback 활성화
        registry.addEndpoint("/ws")
                .setHandshakeHandler(new UserIdHandshakeHandler())
                .withSockJS();
    }
}
