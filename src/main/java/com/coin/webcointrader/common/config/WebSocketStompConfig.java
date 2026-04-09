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
 *   <li>{@code /topic/price.{symbol}} - 실시간 가격 데이터 (예: /topic/price.BTCUSDT)</li>
 *   <li>{@code /topic/autotrade.status.{symbol}} - 자동매매 상태 변경 이벤트</li>
 *   <li>향후 {@code /topic/orderbook.{symbol}}, {@code /topic/kline.{symbol}} 등 확장 가능</li>
 * </ul>
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
        // /topic prefix: 브라우저가 구독할 수 있는 목적지 prefix
        // 서버에서 /topic/... 으로 메시지를 보내면, 해당 토픽을 구독한 브라우저에 전달된다
        registry.enableSimpleBroker("/topic");
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
        // withSockJS(): WebSocket을 지원하지 않는 브라우저를 위한 SockJS fallback 활성화
        registry.addEndpoint("/ws")
                .withSockJS();
    }
}
