package com.coin.webcointrader.bybit.stream;

import com.coin.webcointrader.bybit.market.dto.WebSocketRequest;
import com.coin.webcointrader.bybit.stream.dto.WebSocketAuthRequest;
import com.coin.webcointrader.bybit.stream.dto.WebSocketExecutionDTO;
import com.coin.webcointrader.common.enums.LogMessage;
import com.coin.webcointrader.common.util.HmacUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;

/**
 * 사용자 1명분의 Bybit Private WebSocket 연결.
 * 흐름: 연결 → auth 전송 → 인증 성공 시 execution.linear 구독 → 체결 수신 시 콜백 전달.
 * 끊기면 지수 백오프로 재연결(재연결마다 재인증)하고, 인증 실패나 close() 이후에는 재연결하지 않는다.
 */
@Slf4j
class PrivateConnection extends TextWebSocketHandler {

    private static final String EXECUTION_TOPIC = "execution.linear"; // 체결 토픽 (USDT 무기한만)
    private static final long AUTH_EXPIRES_MS = 10_000L;               // 인증 서명 유효 시간 (현재 시각 + 10초)

    @Getter
    private final Long userId;                  // 사용자 ID
    private final String apiKey;                // API Key
    private final String apiSecret;             // API Secret (서명용, 로그 출력 금지)
    private final BybitPrivateWebSocketClient owner; // 연결 관리자 (설정·스케줄러·콜백 보유)

    private volatile WebSocketSession session;  // 현재 WebSocket 세션
    private volatile boolean stopped;           // 종료 여부 (true면 재연결하지 않음)
    @Getter
    private volatile boolean ready;             // 체결 수신 준비 여부 (구독 성공 ~ 연결 종료 구간만 true)
    private volatile long currentReconnectDelay; // 현재 재연결 대기 시간 (지수 백오프)
    private ScheduledFuture<?> heartbeatTask;   // heartbeat 작업

    PrivateConnection(Long userId, String apiKey, String apiSecret, BybitPrivateWebSocketClient owner) {
        this.userId = userId;
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.owner = owner;
        this.currentReconnectDelay = owner.reconnectDelay();
    }

    /**
     * 비동기 핸드셰이크를 시작한다.
     * 핸드셰이크 실패는 afterConnectionClosed가 호출되지 않으므로 future 실패 시 직접 재연결을 예약한다.
     */
    void connect() {
        if (stopped) return;
        log.info(LogMessage.PRIVATE_WS_CONNECTING.getMessage(), userId);
        try {
            owner.execute(this).whenComplete((s, ex) -> {
                if (ex != null) {
                    log.error(LogMessage.PRIVATE_WS_CONNECT_FAILED.getMessage(), userId, ex.getMessage());
                    scheduleReconnect();
                }
            });
        } catch (Exception e) {
            log.error(LogMessage.PRIVATE_WS_CONNECT_FAILED.getMessage(), userId, e.getMessage());
            scheduleReconnect();
        }
    }

    /**
     * 연결을 종료한다. 이후 재연결하지 않는다.
     */
    void shutdown() {
        stopped = true;
        ready = false;
        stopHeartbeat();
        closeSession();
    }

    // ─────────────────────────────────────────────
    // WebSocket 이벤트 핸들러
    // ─────────────────────────────────────────────

    /**
     * 연결 성공 시 인증 요청을 보내고 heartbeat를 시작한다. 구독은 인증 성공 응답 후 보낸다.
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        this.session = session;
        // 핸드셰이크 중 close()된 경우 바로 닫음
        if (stopped) {
            closeSession();
            return;
        }
        currentReconnectDelay = owner.reconnectDelay();
        sendAuth();
        startHeartbeat();
    }

    /**
     * 수신 메시지를 처리한다.
     * op 메시지는 제어 응답(auth/subscribe/pong), topic 메시지는 데이터로 분기한다.
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonNode node = owner.objectMapper().readTree(message.getPayload());

            if (node.has("op")) {
                handleControl(node);
                return;
            }

            // execution 토픽: 체결 건마다 콜백 전달
            if (node.path("topic").asText().startsWith("execution")) {
                WebSocketExecutionDTO dto = owner.objectMapper().treeToValue(node, WebSocketExecutionDTO.class);
                if (dto.getData() == null) return;
                for (WebSocketExecutionDTO.ExecutionData data : dto.getData()) {
                    log.debug(LogMessage.PRIVATE_WS_EXECUTION_RECEIVED.getMessage(), userId, data.getSymbol(),
                            data.getOrderLinkId(), data.getExecType(), data.getExecPrice(), data.getExecQty(),
                            data.getExecFee());
                    owner.dispatch(userId, data);
                }
            }
        } catch (JsonProcessingException e) {
            log.warn(LogMessage.WS_MESSAGE_PARSE_ERROR.getMessage(), e.getMessage());
        }
    }

    /**
     * 연결 종료 시 heartbeat를 멈추고, 종료 요청이 없었던 연결이면 재연결을 예약한다.
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.warn(LogMessage.PRIVATE_WS_CONNECTION_CLOSED.getMessage(), userId, status);
        // 재연결·재구독 전까지 놓치는 체결이 있으므로 WS 대기 대상에서 제외
        ready = false;
        stopHeartbeat();
        scheduleReconnect();
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error(LogMessage.PRIVATE_WS_TRANSPORT_ERROR.getMessage(), userId, exception.getMessage());
    }

    // ─────────────────────────────────────────────
    // 내부 헬퍼 메서드
    // ─────────────────────────────────────────────

    /**
     * 제어 응답을 처리한다.
     * auth 성공 → 구독 / auth 실패 → 키 오류로 보고 연결 종료(재연결 안 함)
     * subscribe 성공 → 체결 수신 준비(ready) / subscribe 실패 → 경고 로그.
     */
    private void handleControl(JsonNode node) {
        String op = node.path("op").asText();
        boolean success = node.path("success").asBoolean(false);

        if ("auth".equals(op)) {
            if (success) {
                log.info(LogMessage.PRIVATE_WS_AUTH_SUCCESS.getMessage(), userId);
                sendMessage(WebSocketRequest.subscribe(List.of(EXECUTION_TOPIC)));
                log.info(LogMessage.PRIVATE_WS_SUBSCRIBE.getMessage(), userId, EXECUTION_TOPIC);
            } else {
                // 잘못된 키로 무한 재시도하지 않도록 관리 대상에서 제거 후 종료
                log.warn(LogMessage.PRIVATE_WS_AUTH_FAILED.getMessage(), userId, node.path("ret_msg").asText());
                owner.remove(this);
                shutdown();
            }
        } else if ("subscribe".equals(op)) {
            if (success) {
                // 구독 성공 이후부터 체결 push 수신 가능
                ready = true;
            } else {
                log.warn(LogMessage.PRIVATE_WS_SUBSCRIBE_FAILED.getMessage(), userId, node.path("ret_msg").asText());
            }
        }
        // pong 등 나머지 제어 메시지는 무시
    }

    /**
     * 인증 요청을 보낸다. 서명 = hex(HMAC_SHA256(secret, "GET/realtime" + expires))
     */
    private void sendAuth() {
        long expires = System.currentTimeMillis() + AUTH_EXPIRES_MS;
        String signature = HmacUtil.sha256Hex(apiSecret, "GET/realtime" + expires);
        sendMessage(WebSocketAuthRequest.of(apiKey, expires, signature));
    }

    /**
     * 지수 백오프로 재연결을 예약한다. 종료됐거나 관리 대상에서 빠진 연결은 예약하지 않는다.
     * 대기 시간: reconnectDelay → 2배 → 4배 → ... → reconnectMaxDelay
     */
    private void scheduleReconnect() {
        if (stopped || !owner.isActive(this)) return;

        long delay = currentReconnectDelay;
        currentReconnectDelay = Math.min(currentReconnectDelay * 2, owner.reconnectMaxDelay());
        log.info(LogMessage.PRIVATE_WS_RECONNECT_SCHEDULED.getMessage(), userId, delay);
        try {
            owner.schedule(this::connect, delay);
        } catch (RejectedExecutionException e) {
            // 애플리케이션 종료 중 — 재연결하지 않음
        }
    }

    private synchronized void startHeartbeat() {
        stopHeartbeat();
        try {
            heartbeatTask = owner.scheduleHeartbeat(() -> sendMessage(WebSocketRequest.ping()));
        } catch (RejectedExecutionException e) {
            // 애플리케이션 종료 중 — heartbeat 불필요
        }
    }

    private synchronized void stopHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(true);
            heartbeatTask = null;
        }
    }

    private void closeSession() {
        WebSocketSession s = session;
        if (s != null && s.isOpen()) {
            try {
                s.close();
            } catch (IOException e) {
                log.warn(LogMessage.WS_SESSION_CLOSE_ERROR.getMessage(), e.getMessage());
            }
        }
    }

    /**
     * JSON 메시지를 전송한다.
     * heartbeat 스레드와 수신 스레드가 동시에 보낼 수 있어 동기화한다. (WebSocketSession 전송은 스레드 안전하지 않음)
     */
    private synchronized void sendMessage(Object request) {
        WebSocketSession s = session;
        if (s == null || !s.isOpen()) return;
        try {
            s.sendMessage(new TextMessage(owner.objectMapper().writeValueAsString(request)));
        } catch (IOException e) {
            log.error(LogMessage.WS_MESSAGE_SEND_FAILED.getMessage(), e.getMessage());
        }
    }
}
