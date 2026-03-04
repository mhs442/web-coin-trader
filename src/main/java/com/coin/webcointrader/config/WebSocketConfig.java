package com.coin.webcointrader.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

/**
 * WebSocket 클라이언트 빈 설정.
 */
@Configuration
public class WebSocketConfig {

    /**
     * Bybit WebSocket 연결에 사용할 StandardWebSocketClient 빈을 등록한다.
     *
     * @return StandardWebSocketClient 인스턴스
     */
    @Bean
    public StandardWebSocketClient webSocketClient() {
        return new StandardWebSocketClient();
    }
}
