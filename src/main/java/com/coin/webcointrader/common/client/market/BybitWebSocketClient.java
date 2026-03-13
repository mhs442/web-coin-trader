package com.coin.webcointrader.common.client.market;

import com.coin.webcointrader.common.client.market.dto.WebSocketRequest;
import com.coin.webcointrader.common.client.market.dto.WebSocketTickerDTO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Bybit WebSocket 클라이언트.
 * 실시간 티커 데이터를 수신하여 MarketService에 전달한다.
 *
 * 주요 기능:
 * - Bybit WebSocket 연결 및 자동 재연결 (지수 백오프)
 * - 20초 주기 heartbeat(ping) 전송
 * - 심볼별 구독/구독해제 관리
 * - 수신 메시지를 WebSocketTickerDTO로 파싱하여 콜백 호출
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BybitWebSocketClient extends TextWebSocketHandler {

    private final StandardWebSocketClient webSocketClient;
    private final ObjectMapper objectMapper;

    @Value("${bybit.websocket.url}")
    private String websocketUrl;                // WebSocket 서버 URL

    @Value("${bybit.websocket.ping-interval}")
    private long pingInterval;                  // heartbeat 주기 (밀리초)

    @Value("${bybit.websocket.reconnect-delay}")
    private long reconnectDelay;                // 초기 재연결 대기 시간 (밀리초)

    @Value("${bybit.websocket.reconnect-max-delay}")
    private long reconnectMaxDelay;             // 최대 재연결 대기 시간 (밀리초)

    private volatile WebSocketSession session;  // 현재 WebSocket 세션

    // 현재 구독 중인 심볼 목록
    private final Set<String> subscribedSymbols = ConcurrentHashMap.newKeySet();

    // 티커 데이터 수신 콜백
    private Consumer<WebSocketTickerDTO> tickerCallback;

    // heartbeat 스케줄러
    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> heartbeatTask;

    // 재연결 스케줄러
    private final ScheduledExecutorService reconnectScheduler = Executors.newSingleThreadScheduledExecutor();

    // 현재 재연결 대기 시간 (지수 백오프)
    private volatile long currentReconnectDelay;

    // 연결 상태 플래그
    private volatile boolean shouldConnect = true;

    /**
     * 애플리케이션 시작 시 Bybit WebSocket에 연결한다.
     */
    @PostConstruct
    public void init() {
        currentReconnectDelay = reconnectDelay;
        connect();
    }

    /**
     * 애플리케이션 종료 시 WebSocket 연결을 정리한다.
     */
    @PreDestroy
    public void destroy() {
        shouldConnect = false;
        // heartbeat 스케줄러 종료
        if (heartbeatTask != null) {
            heartbeatTask.cancel(true);
        }
        heartbeatScheduler.shutdownNow();
        reconnectScheduler.shutdownNow();

        // WebSocket 세션 종료
        if (session != null && session.isOpen()) {
            try {
                session.close();
            } catch (IOException e) {
                log.warn("WebSocket 세션 종료 중 오류: {}", e.getMessage());
            }
        }
    }

    /**
     * 티커 데이터 수신 콜백을 등록한다.
     *
     * @param callback WebSocketTickerDTO를 처리할 콜백 함수
     */
    public void setTickerCallback(Consumer<WebSocketTickerDTO> callback) {
        this.tickerCallback = callback;
    }

    /**
     * 특정 심볼의 티커를 구독한다.
     *
     * @param symbol 구독할 심볼 (예: "BTCUSDT")
     */
    public void subscribe(String symbol) {
        // 이미 구독 중이면 무시
        if (!subscribedSymbols.add(symbol)) {
            return;
        }

        String topic = "tickers." + symbol;
        sendMessage(WebSocketRequest.subscribe(List.of(topic)));
        log.info("WebSocket 구독 요청: {}", topic);
    }

    /**
     * 특정 심볼의 티커 구독을 해제한다.
     *
     * @param symbol 구독 해제할 심볼 (예: "BTCUSDT")
     */
    public void unsubscribe(String symbol) {
        // 구독 중이 아니면 무시
        if (!subscribedSymbols.remove(symbol)) {
            return;
        }

        String topic = "tickers." + symbol;
        sendMessage(WebSocketRequest.unsubscribe(List.of(topic)));
        log.info("WebSocket 구독 해제 요청: {}", topic);
    }

    /**
     * 구독 목록을 주어진 심볼 집합과 동기화한다.
     * 새로운 심볼은 구독하고, 불필요한 심볼은 구독 해제한다.
     *
     * @param targetSymbols 활성 세션의 심볼 집합
     */
    public void syncSubscriptions(Set<String> targetSymbols) {
        // 새로 추가된 심볼 구독
        for (String symbol : targetSymbols) {
            if (!subscribedSymbols.contains(symbol)) {
                subscribe(symbol);
            }
        }

        // 제거된 심볼 구독 해제
        for (String symbol : Set.copyOf(subscribedSymbols)) {
            if (!targetSymbols.contains(symbol)) {
                unsubscribe(symbol);
            }
        }
    }

    /**
     * 현재 구독 중인 심볼 목록을 반환한다.
     *
     * @return 구독 중인 심볼 Set (읽기 전용 복사본)
     */
    public Set<String> getSubscribedSymbols() {
        return Set.copyOf(subscribedSymbols);
    }

    // ─────────────────────────────────────────────
    // WebSocket 이벤트 핸들러
    // ─────────────────────────────────────────────

    /**
     * WebSocket 연결 성공 시 호출된다.
     * heartbeat를 시작하고, 기존 구독 토픽을 재구독한다.
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        this.session = session;
        // 연결 성공 시 재연결 대기 시간 초기화
        currentReconnectDelay = reconnectDelay;
        log.info("Bybit WebSocket 연결 성공: {}", websocketUrl);

        // heartbeat 시작
        startHeartbeat();

        // 기존 구독 토픽 재구독
        resubscribeAll();
    }

    /**
     * WebSocket 메시지 수신 시 호출된다.
     * 티커 데이터 메시지를 파싱하여 콜백으로 전달한다.
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();

        try {
            // pong 응답이나 구독 확인 메시지는 무시
            JsonNode node = objectMapper.readTree(payload);
            if (node.has("op")) {
                // op 필드가 있으면 제어 메시지 (pong, subscribe 응답 등)
                return;
            }

            // topic 필드가 있으면 티커 데이터 메시지
            if (node.has("topic") && node.get("topic").asText().startsWith("tickers.")) {
                WebSocketTickerDTO dto = objectMapper.readValue(payload, WebSocketTickerDTO.class);
                if (tickerCallback != null) {
                    tickerCallback.accept(dto);
                }
            }
        } catch (JsonProcessingException e) {
            log.warn("WebSocket 메시지 파싱 오류: {}", e.getMessage());
        }
    }

    /**
     * WebSocket 연결이 끊어졌을 때 호출된다.
     * 지수 백오프 방식으로 재연결을 시도한다.
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.warn("Bybit WebSocket 연결 종료: status={}", status);

        // heartbeat 중지
        stopHeartbeat();

        // 재연결 시도
        if (shouldConnect) {
            scheduleReconnect();
        }
    }

    /**
     * WebSocket 전송 오류 발생 시 호출된다.
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("Bybit WebSocket 전송 오류: {}", exception.getMessage());
    }

    // ─────────────────────────────────────────────
    // 내부 헬퍼 메서드
    // ─────────────────────────────────────────────

    /**
     * Bybit WebSocket 서버에 연결한다.
     */
    private void connect() {
        try {
            log.info("Bybit WebSocket 연결 시도: {}", websocketUrl);
            webSocketClient.execute(this, null, URI.create(websocketUrl));
        } catch (Exception e) {
            log.error("Bybit WebSocket 연결 실패: {}", e.getMessage());
            // 연결 실패 시 재연결 스케줄
            if (shouldConnect) {
                scheduleReconnect();
            }
        }
    }

    /**
     * 지수 백오프 방식으로 재연결을 스케줄한다.
     * 대기 시간: reconnectDelay → 2배 → 4배 → ... → reconnectMaxDelay
     */
    private void scheduleReconnect() {
        long delay = currentReconnectDelay;
        // 다음 재연결 대기 시간을 2배로 증가 (최대값 제한)
        currentReconnectDelay = Math.min(currentReconnectDelay * 2, reconnectMaxDelay);

        log.info("Bybit WebSocket 재연결 예약: {}ms 후", delay);
        reconnectScheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
    }

    /**
     * 주기적으로 ping 메시지를 전송하여 연결을 유지한다.
     */
    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatTask = heartbeatScheduler.scheduleAtFixedRate(() -> {
            sendMessage(WebSocketRequest.ping());
        }, pingInterval, pingInterval, TimeUnit.MILLISECONDS);
    }

    /**
     * heartbeat 전송을 중지한다.
     */
    private void stopHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(true);
            heartbeatTask = null;
        }
    }

    /**
     * 기존 구독 토픽을 모두 재구독한다. (재연결 시 사용)
     */
    private void resubscribeAll() {
        if (subscribedSymbols.isEmpty()) {
            return;
        }

        List<String> topics = subscribedSymbols.stream()
                .map(symbol -> "tickers." + symbol)
                .toList();

        sendMessage(WebSocketRequest.subscribe(topics));
        log.info("WebSocket 재구독 완료: {}개 심볼", subscribedSymbols.size());
    }

    /**
     * WebSocket 세션에 JSON 메시지를 전송한다.
     *
     * @param request 전송할 요청 객체
     */
    private void sendMessage(WebSocketRequest request) {
        if (session == null || !session.isOpen()) {
            return;
        }

        try {
            String json = objectMapper.writeValueAsString(request);
            session.sendMessage(new TextMessage(json));
        } catch (IOException e) {
            log.error("WebSocket 메시지 전송 실패: {}", e.getMessage());
        }
    }
}
