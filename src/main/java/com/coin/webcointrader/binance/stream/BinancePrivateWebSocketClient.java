package com.coin.webcointrader.binance.stream;

import com.coin.webcointrader.binance.stream.dto.BinanceOrderTradeUpdateDTO;
import com.coin.webcointrader.common.enums.LogMessage;
import com.coin.webcointrader.common.util.UserApiKeyContext;
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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Binance user data stream(개인 스트림) 연결 관리자.
 * 사용자(API Key)별로 개인 스트림 연결을 1개씩 유지하고, 주문·체결(ORDER_TRADE_UPDATE) 이벤트를 콜백으로 전달한다.
 *
 * 주요 기능:
 * - 사용자별 연결 열기/닫기 (같은 사용자는 연결 1개만 유지)
 * - 연결별 listenKey 발급 → 접속 → 주기적 연장 → 자동 재접속 (지수 백오프, 재접속마다 listenKey 재발급)
 * - 수신한 주문 업데이트를 (userId, 주문 정보) 콜백으로 전달
 *
 * API 명세:
 * - 접속: {@code binance.websocket.private-url} 의 {listenKey} 자리에 발급받은 키를 넣어 접속 (메시지 전송 없이 수신만)
 * - listenKey: 계정당 1개, 60분 유효 → PUT 으로 연장, 만료 시 listenKeyExpired 이벤트 수신
 * - 연결은 24시간 후 서버가 끊음 → 재접속
 * - 재접속 시 놓친 이벤트는 재전송되지 않으므로 호출부는 REST 체결 조회 fallback 을 유지해야 한다.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BinancePrivateWebSocketClient {

    private static final String LISTEN_KEY_PLACEHOLDER = "{listenKey}"; // 접속 주소의 listenKey 자리

    private final StandardWebSocketClient webSocketClient;
    private final ObjectMapper objectMapper;
    private final BinanceUserStreamFeignClient userStreamFeignClient;

    @Value("${binance.websocket.private-url}")
    private String privateUrl;                  // 개인 스트림 접속 주소 ({listenKey} 포함)

    @Value("${binance.websocket.listen-key-keepalive}")
    private long listenKeyKeepAlive;            // listenKey 연장 주기 (밀리초)

    @Value("${binance.websocket.reconnect-delay}")
    private long reconnectDelay;                // 초기 재접속 대기 시간 (밀리초)

    @Value("${binance.websocket.reconnect-max-delay}")
    private long reconnectMaxDelay;             // 최대 재접속 대기 시간 (밀리초)

    // 사용자 ID → 연결 (사용자당 1개)
    private final Map<Long, BinancePrivateConnection> connections = new ConcurrentHashMap<>();

    // 주문 업데이트 수신 콜백 목록 (userId, 주문 정보)
    private final List<BiConsumer<Long, BinanceOrderTradeUpdateDTO.OrderUpdate>> orderUpdateCallbacks =
            new CopyOnWriteArrayList<>();

    // listenKey 발급·연장·폐기, 접속·재접속 공용 스케줄러
    // (REST 호출은 가중치 1의 짧은 요청이고 순서대로 실행돼야 close 직후 open 시 폐기→발급 순서가 보장되므로 단일 스레드)
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> new Thread(r, "binance-private-ws-scheduler"));

    /**
     * 사용자의 개인 스트림 연결을 연다. 이미 연결(또는 연결 시도) 중이면 무시한다.
     * listenKey 발급과 접속은 스케줄러 스레드에서 비동기로 진행된다.
     *
     * @param userId 사용자 ID
     * @param apiKey 복호화된 API Key (listenKey 는 서명 없이 키만 필요)
     */
    public void open(Long userId, String apiKey) {
        BinancePrivateConnection conn = new BinancePrivateConnection(userId, apiKey, this);
        // 같은 사용자의 연결이 이미 있으면 새 연결을 만들지 않음
        if (connections.putIfAbsent(userId, conn) != null) {
            return;
        }
        conn.connect();
    }

    /**
     * 사용자의 개인 스트림 연결을 닫고 listenKey 를 폐기한다. 닫힌 연결은 재접속하지 않는다.
     * 폐기 실패는 무시한다. (연장하지 않으면 60분 뒤 자동 만료)
     *
     * @param userId 사용자 ID
     */
    public void close(Long userId) {
        BinancePrivateConnection conn = connections.remove(userId);
        if (conn == null) return;
        conn.shutdown();
        try {
            scheduler.execute(() -> closeListenKey(conn));
        } catch (RejectedExecutionException e) {
            // 애플리케이션 종료 중 — 자동 만료에 맡김
        }
    }

    /**
     * 주문 업데이트 수신 콜백을 추가한다.
     * 콜백은 WebSocket 수신 스레드에서 호출되므로 오래 걸리는 작업은 다른 스레드로 넘겨야 한다.
     *
     * @param callback (userId, 주문 정보)를 처리할 콜백
     */
    public void addOrderUpdateCallback(BiConsumer<Long, BinanceOrderTradeUpdateDTO.OrderUpdate> callback) {
        orderUpdateCallbacks.add(callback);
    }

    /**
     * 연결(또는 연결 시도·재접속 대기) 중인 사용자 ID 목록을 반환한다.
     *
     * @return 사용자 ID Set (읽기 전용 복사본)
     */
    public Set<Long> getConnectedUserIds() {
        return Set.copyOf(connections.keySet());
    }

    /**
     * 사용자의 개인 스트림이 체결을 받을 수 있는 상태(접속 완료, 연결 유지 중)인지 확인한다.
     *
     * @param userId 사용자 ID
     * @return 체결 수신 준비 상태면 true, 연결이 없거나 연결·재접속 중이면 false
     */
    public boolean isReady(Long userId) {
        BinancePrivateConnection conn = connections.get(userId);
        return conn != null && conn.isReady();
    }

    /**
     * 애플리케이션 종료 시 모든 연결을 닫고 스케줄러를 종료한다.
     * listenKey 폐기는 종료를 늦추지 않도록 생략한다. (60분 뒤 자동 만료)
     */
    @PreDestroy
    public void destroy() {
        for (BinancePrivateConnection conn : connections.values()) {
            conn.shutdown();
        }
        connections.clear();
        scheduler.shutdownNow();
    }

    // ─────────────────────────────────────────────
    // BinancePrivateConnection 전용 (패키지 내부)
    // ─────────────────────────────────────────────

    /** 테스트·내부용: 사용자의 현재 연결을 반환한다. */
    BinancePrivateConnection connection(Long userId) {
        return connections.get(userId);
    }

    /** 연결이 아직 관리 대상(닫히거나 교체되지 않음)인지 확인한다. */
    boolean isActive(BinancePrivateConnection conn) {
        return connections.get(conn.getUserId()) == conn;
    }

    /** 관리 대상에서 연결을 제거한다. (키 오류 시) */
    void remove(BinancePrivateConnection conn) {
        connections.remove(conn.getUserId(), conn);
    }

    /** 사용자 키로 listenKey 를 발급받는다. (이미 있으면 같은 키 + 60분 연장) */
    String createListenKey(String apiKey) {
        return withApiKey(apiKey, () -> userStreamFeignClient.createListenKey().getListenKey());
    }

    /** 사용자 키로 listenKey 유효기간을 연장한다. */
    void keepAliveListenKey(String apiKey) {
        withApiKey(apiKey, userStreamFeignClient::keepAliveListenKey);
    }

    /** 비동기 핸드셰이크를 시작한다. 접속 주소의 {listenKey} 자리에 발급받은 키를 넣는다. */
    CompletableFuture<WebSocketSession> execute(BinancePrivateConnection conn, String listenKey) {
        return webSocketClient.execute(conn, null, URI.create(privateUrl.replace(LISTEN_KEY_PLACEHOLDER, listenKey)));
    }

    /** 지연 후 작업을 1회 실행한다. (접속·재접속) */
    void schedule(Runnable task, long delayMs) {
        scheduler.schedule(task, delayMs, TimeUnit.MILLISECONDS);
    }

    /** listenKey 연장 작업을 주기적으로 실행한다. */
    ScheduledFuture<?> scheduleKeepAlive(Runnable task) {
        return scheduler.scheduleAtFixedRate(task, listenKeyKeepAlive, listenKeyKeepAlive, TimeUnit.MILLISECONDS);
    }

    /** 주문 업데이트를 모든 콜백에 전달한다. 콜백 예외는 로그만 남기고 다음 콜백 처리를 계속한다. */
    void dispatch(Long userId, BinanceOrderTradeUpdateDTO.OrderUpdate order) {
        for (BiConsumer<Long, BinanceOrderTradeUpdateDTO.OrderUpdate> cb : orderUpdateCallbacks) {
            try {
                cb.accept(userId, order);
            } catch (Exception e) {
                log.warn(LogMessage.BINANCE_PRIVATE_WS_CALLBACK_ERROR.getMessage(), userId, order.getClientOrderId(),
                        e.getMessage());
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

    // ─────────────────────────────────────────────
    // 내부 헬퍼 메서드
    // ─────────────────────────────────────────────

    /** listenKey 를 폐기한다. 실패는 로그만 남긴다. */
    private void closeListenKey(BinancePrivateConnection conn) {
        try {
            withApiKey(conn.getApiKey(), () -> {
                userStreamFeignClient.closeListenKey();
                return null;
            });
        } catch (Exception e) {
            log.warn(LogMessage.BINANCE_PRIVATE_WS_CLOSE_LISTEN_KEY_FAILED.getMessage(), conn.getUserId(), e.getMessage());
        }
    }

    /** 사용자 키를 UserApiKeyContext 에 바인딩한 채로 listenKey API 를 호출한다. (서명 불필요 → secret 없음) */
    private <T> T withApiKey(String apiKey, Supplier<T> call) {
        UserApiKeyContext.set(apiKey, null);
        try {
            return call.get();
        } finally {
            UserApiKeyContext.clear();
        }
    }
}
