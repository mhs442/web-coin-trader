package com.coin.webcointrader.bybit.client.privatews;

import com.coin.webcointrader.bybit.client.privatews.dto.WebSocketExecutionDTO;
import com.coin.webcointrader.common.enums.LogMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Bybit Private WebSocket 연결 관리자.
 * 사용자(API Key)별로 private 스트림 연결을 1개씩 유지하고, 체결(execution) 메시지를 콜백으로 전달한다.
 *
 * 주요 기능:
 * - 사용자별 연결 열기/닫기 (같은 사용자는 연결 1개만 유지)
 * - 연결별 인증(auth) → execution.linear 구독 → heartbeat → 자동 재연결 (지수 백오프)
 * - 수신한 체결 건을 (userId, 체결 데이터) 콜백으로 전달
 *
 * API 명세: wss://stream.bybit.com/v5/private
 * - 인증: {"op":"auth","args":[apiKey, expires(ms), hex(HMAC_SHA256(secret, "GET/realtime" + expires))]}
 * - 구독: {"op":"subscribe","args":["execution.linear"]}
 * - heartbeat: 20초 주기 {"op":"ping"} (ping·데이터 모두 없으면 10분 후 서버가 연결 종료)
 * - 재연결 시 놓친 메시지는 재전송되지 않으므로 호출부는 REST 체결 조회 fallback을 유지해야 한다.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BybitPrivateWebSocketClient {

    private final StandardWebSocketClient webSocketClient;
    private final ObjectMapper objectMapper;

    @Value("${bybit.websocket.private-url}")
    private String privateUrl;                  // Private WebSocket 서버 URL

    @Value("${bybit.websocket.ping-interval}")
    private long pingInterval;                  // heartbeat 주기 (밀리초)

    @Value("${bybit.websocket.reconnect-delay}")
    private long reconnectDelay;                // 초기 재연결 대기 시간 (밀리초)

    @Value("${bybit.websocket.reconnect-max-delay}")
    private long reconnectMaxDelay;             // 최대 재연결 대기 시간 (밀리초)

    // 사용자 ID → 연결 (사용자당 1개)
    private final Map<Long, PrivateConnection> connections = new ConcurrentHashMap<>();

    // 체결 수신 콜백 목록 (userId, 체결 건)
    private final List<BiConsumer<Long, WebSocketExecutionDTO.ExecutionData>> executionCallbacks =
            new CopyOnWriteArrayList<>();

    // heartbeat·재연결 공용 스케줄러 (작업은 메시지 전송/비동기 연결 시작뿐이라 단일 스레드로 충분)
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> new Thread(r, "private-ws-scheduler"));

    /**
     * 사용자의 private 스트림 연결을 연다. 이미 연결(또는 연결 시도) 중이면 무시한다.
     *
     * @param userId    사용자 ID
     * @param apiKey    복호화된 API Key
     * @param apiSecret 복호화된 API Secret (인증 서명에만 사용, 로그 출력 금지)
     */
    public void open(Long userId, String apiKey, String apiSecret) {
        PrivateConnection conn = new PrivateConnection(userId, apiKey, apiSecret, this);
        // 같은 사용자의 연결이 이미 있으면 새 연결을 만들지 않음
        if (connections.putIfAbsent(userId, conn) != null) {
            return;
        }
        conn.connect();
    }

    /**
     * 사용자의 private 스트림 연결을 닫는다. 닫힌 연결은 재연결하지 않는다.
     *
     * @param userId 사용자 ID
     */
    public void close(Long userId) {
        PrivateConnection conn = connections.remove(userId);
        if (conn != null) {
            conn.shutdown();
        }
    }

    /**
     * 체결 수신 콜백을 추가한다.
     * 콜백은 WebSocket 수신 스레드에서 호출되므로 오래 걸리는 작업은 다른 스레드로 넘겨야 한다.
     *
     * @param callback (userId, 체결 건)을 처리할 콜백
     */
    public void addExecutionCallback(BiConsumer<Long, WebSocketExecutionDTO.ExecutionData> callback) {
        executionCallbacks.add(callback);
    }

    /**
     * 연결(또는 연결 시도·재연결 대기) 중인 사용자 ID 목록을 반환한다.
     *
     * @return 사용자 ID Set (읽기 전용 복사본)
     */
    public Set<Long> getConnectedUserIds() {
        return Set.copyOf(connections.keySet());
    }

    /**
     * 사용자의 private 스트림이 체결을 받을 수 있는 상태(인증·구독 완료, 연결 유지 중)인지 확인한다.
     *
     * @param userId 사용자 ID
     * @return 체결 수신 준비 상태면 true, 연결이 없거나 연결·재연결 중이면 false
     */
    public boolean isReady(Long userId) {
        PrivateConnection conn = connections.get(userId);
        return conn != null && conn.isReady();
    }

    /**
     * 애플리케이션 종료 시 모든 연결을 닫고 스케줄러를 종료한다.
     */
    @PreDestroy
    public void destroy() {
        for (Long userId : Set.copyOf(connections.keySet())) {
            close(userId);
        }
        scheduler.shutdownNow();
    }

    // ─────────────────────────────────────────────
    // PrivateConnection 전용 (패키지 내부)
    // ─────────────────────────────────────────────

    /** 테스트·내부용: 사용자의 현재 연결을 반환한다. */
    PrivateConnection connection(Long userId) {
        return connections.get(userId);
    }

    /** 연결이 아직 관리 대상(닫히거나 교체되지 않음)인지 확인한다. */
    boolean isActive(PrivateConnection conn) {
        return connections.get(conn.getUserId()) == conn;
    }

    /** 관리 대상에서 연결을 제거한다. (인증 실패 시) */
    void remove(PrivateConnection conn) {
        connections.remove(conn.getUserId(), conn);
    }

    /** 비동기 핸드셰이크를 시작한다. */
    CompletableFuture<WebSocketSession> execute(PrivateConnection conn) {
        return webSocketClient.execute(conn, null, URI.create(privateUrl));
    }

    /** 지연 후 작업을 1회 실행한다. (재연결) */
    void schedule(Runnable task, long delayMs) {
        scheduler.schedule(task, delayMs, TimeUnit.MILLISECONDS);
    }

    /** heartbeat 작업을 주기적으로 실행한다. */
    ScheduledFuture<?> scheduleHeartbeat(Runnable task) {
        return scheduler.scheduleAtFixedRate(task, pingInterval, pingInterval, TimeUnit.MILLISECONDS);
    }

    /** 체결 건을 모든 콜백에 전달한다. 콜백 예외는 로그만 남기고 다음 콜백·체결 건 처리를 계속한다. */
    void dispatch(Long userId, WebSocketExecutionDTO.ExecutionData data) {
        for (BiConsumer<Long, WebSocketExecutionDTO.ExecutionData> cb : executionCallbacks) {
            try {
                cb.accept(userId, data);
            } catch (Exception e) {
                log.warn(LogMessage.PRIVATE_WS_CALLBACK_ERROR.getMessage(), userId, data.getExecId(), e.getMessage());
            }
        }
    }

    ObjectMapper objectMapper() {
        return objectMapper;
    }

    long reconnectDelay() {
        return reconnectDelay;
    }

    long reconnectMaxDelay() {
        return reconnectMaxDelay;
    }
}
