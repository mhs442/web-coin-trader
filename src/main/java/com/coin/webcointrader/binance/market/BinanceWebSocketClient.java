package com.coin.webcointrader.binance.market;

import com.coin.webcointrader.binance.market.dto.BinanceWsAggTradeDTO;
import com.coin.webcointrader.binance.market.dto.BinanceWsKlineDTO;
import com.coin.webcointrader.binance.market.dto.BinanceWsRequest;
import com.coin.webcointrader.binance.market.dto.BinanceWsTickerDTO;
import com.coin.webcointrader.common.enums.LogMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Binance USDⓈ-M 공개 WebSocket(/market) 클라이언트.
 * 심볼별 24시간 티커(@ticker)와 1분봉(@kline_1m)을 받아 콜백으로 전달한다.
 *
 * 주요 기능:
 * - 연결 및 자동 재연결 (지수 백오프)
 * - 구독 변경을 모아 1초마다 SUBSCRIBE·UNSUBSCRIBE 최대 1건씩만 전송 (연결당 초당 수신 메시지 10건 제한 대응)
 * - ping은 서버가 3분마다 보내고 컨테이너가 pong을 자동 응답하므로 별도 heartbeat 없음
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BinanceWebSocketClient extends TextWebSocketHandler {

    private final StandardWebSocketClient webSocketClient;
    private final ObjectMapper objectMapper;

    @Value("${binance.websocket.url}")
    private String websocketUrl;                // WebSocket 서버 URL

    @Value("${binance.websocket.reconnect-delay}")
    private long reconnectDelay;                // 초기 재연결 대기 시간 (밀리초)

    @Value("${binance.websocket.reconnect-max-delay}")
    private long reconnectMaxDelay;             // 최대 재연결 대기 시간 (밀리초)

    private volatile WebSocketSession session;  // 현재 WebSocket 세션

    // 구독해야 하는 심볼 (자동매매 활성 심볼, 대문자)
    private volatile Set<String> targetSymbols = Set.of();

    // 실제로 구독 요청을 보낸 심볼 (대문자, ticker·kline 함께 관리)
    private final Set<String> subscribedSymbols = ConcurrentHashMap.newKeySet();

    // 체결(aggTrade)을 구독해야 하는 심볼 (거래량 급변 감시 대상, 대문자)
    private volatile Set<String> targetTradeSymbols = Set.of();

    // 실제로 aggTrade 구독 요청을 보낸 심볼 (대문자)
    private final Set<String> subscribedTradeSymbols = ConcurrentHashMap.newKeySet();

    // 티커 데이터 수신 콜백 목록
    private final List<Consumer<BinanceWsTickerDTO>> tickerCallbacks = new CopyOnWriteArrayList<>();

    // Kline(봉) 데이터 수신 콜백 목록
    private final List<Consumer<BinanceWsKlineDTO>> klineCallbacks = new CopyOnWriteArrayList<>();

    // 집계 체결 수신 콜백 목록
    private final List<Consumer<BinanceWsAggTradeDTO>> tradeCallbacks = new CopyOnWriteArrayList<>();

    // 구독 요청 id (응답 매칭용, 증가값)
    private final AtomicLong requestId = new AtomicLong();

    // 재연결 스케줄러
    private final ScheduledExecutorService reconnectScheduler = Executors.newSingleThreadScheduledExecutor();

    // 현재 재연결 대기 시간 (지수 백오프)
    private volatile long currentReconnectDelay;

    // 연결 상태 플래그
    private volatile boolean shouldConnect = true;

    /**
     * 애플리케이션 시작 시 Binance WebSocket에 연결한다.
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
        reconnectScheduler.shutdownNow();
        if (session != null && session.isOpen()) {
            try {
                session.close();
            } catch (IOException e) {
                log.warn(LogMessage.BINANCE_WS_SESSION_CLOSE_ERROR.getMessage(), e.getMessage());
            }
        }
    }

    /**
     * 티커 데이터 수신 콜백을 추가한다.
     *
     * @param callback BinanceWsTickerDTO를 처리할 콜백
     */
    public void addTickerCallback(Consumer<BinanceWsTickerDTO> callback) {
        tickerCallbacks.add(callback);
    }

    /**
     * Kline 데이터 수신 콜백을 추가한다.
     *
     * @param callback BinanceWsKlineDTO를 처리할 콜백
     */
    public void addKlineCallback(Consumer<BinanceWsKlineDTO> callback) {
        klineCallbacks.add(callback);
    }

    /**
     * 집계 체결 수신 콜백을 추가한다.
     *
     * @param callback BinanceWsAggTradeDTO를 처리할 콜백
     */
    public void addTradeCallback(Consumer<BinanceWsAggTradeDTO> callback) {
        tradeCallbacks.add(callback);
    }

    /**
     * 체결(aggTrade)을 구독할 심볼 집합을 바꾼다. 실제 전송은 {@link #flushSubscriptions()}에서 ticker·kline과 묶어서 한다.
     *
     * @param symbols 거래량 급변 감시 대상 심볼 집합 (예: "BTCUSDT")
     */
    public void syncTradeSubscriptions(Set<String> symbols) {
        targetTradeSymbols = Set.copyOf(symbols);
    }

    /**
     * 현재 aggTrade 구독 중인 심볼 목록을 반환한다.
     *
     * @return 구독 중인 심볼 Set (읽기 전용 복사본)
     */
    public Set<String> getSubscribedTradeSymbols() {
        return Set.copyOf(subscribedTradeSymbols);
    }

    /**
     * 구독할 심볼 집합을 바꾼다. 실제 전송은 {@link #flushSubscriptions()}에서 묶어서 한다.
     *
     * @param symbols 활성 세션의 심볼 집합 (예: "BTCUSDT")
     */
    public void syncSubscriptions(Set<String> symbols) {
        targetSymbols = Set.copyOf(symbols);
    }

    /**
     * 현재 구독 중인 심볼 목록을 반환한다.
     *
     * @return 구독 중인 심볼 Set (읽기 전용 복사본)
     */
    public Set<String> getSubscribedSymbols() {
        return Set.copyOf(subscribedSymbols);
    }

    /**
     * 목표 심볼과 구독 중인 심볼의 차이를 1초마다 한 번에 전송한다.
     * 추가분은 SUBSCRIBE 1건, 제외분은 UNSUBSCRIBE 1건으로 묶는다. (ticker·kline·aggTrade 모두 한 메시지에 — 연결당 초당 메시지 수 제한 대응)
     * 연결 전이거나 전송에 실패하면 구독 상태를 바꾸지 않아 다음 주기에 다시 시도한다.
     */
    @Scheduled(fixedRate = 1000)
    public synchronized void flushSubscriptions() {
        if (session == null || !session.isOpen()) {
            return;
        }
        Set<String> target = targetSymbols;
        Set<String> tradeTarget = targetTradeSymbols;

        Set<String> toAdd = new HashSet<>(target);
        toAdd.removeAll(subscribedSymbols);
        Set<String> tradeToAdd = new HashSet<>(tradeTarget);
        tradeToAdd.removeAll(subscribedTradeSymbols);
        List<String> addStreams = streamsOf(toAdd);
        addStreams.addAll(tradeStreamsOf(tradeToAdd));
        if (!addStreams.isEmpty() && send(BinanceWsRequest.subscribe(addStreams, requestId.incrementAndGet()))) {
            subscribedSymbols.addAll(toAdd);
            subscribedTradeSymbols.addAll(tradeToAdd);
            log.info(LogMessage.BINANCE_WS_SUBSCRIPTION_SENT.getMessage(), "SUBSCRIBE", addStreams.size());
        }

        Set<String> toRemove = new HashSet<>(subscribedSymbols);
        toRemove.removeAll(target);
        Set<String> tradeToRemove = new HashSet<>(subscribedTradeSymbols);
        tradeToRemove.removeAll(tradeTarget);
        List<String> removeStreams = streamsOf(toRemove);
        removeStreams.addAll(tradeStreamsOf(tradeToRemove));
        if (!removeStreams.isEmpty() && send(BinanceWsRequest.unsubscribe(removeStreams, requestId.incrementAndGet()))) {
            subscribedSymbols.removeAll(toRemove);
            subscribedTradeSymbols.removeAll(tradeToRemove);
            log.info(LogMessage.BINANCE_WS_SUBSCRIPTION_SENT.getMessage(), "UNSUBSCRIBE", removeStreams.size());
        }
    }

    // ─────────────────────────────────────────────
    // WebSocket 이벤트 핸들러
    // ─────────────────────────────────────────────

    /**
     * WebSocket 연결 성공 시 호출된다.
     * 새 연결에는 구독이 없으므로 구독 상태를 비워, 다음 flush에서 목표 심볼 전체를 한 번에 구독하게 한다.
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        subscribedSymbols.clear();
        subscribedTradeSymbols.clear();
        this.session = session;
        currentReconnectDelay = reconnectDelay;
        log.info(LogMessage.BINANCE_WS_CONNECTED.getMessage(), websocketUrl);
    }

    /**
     * WebSocket 메시지 수신 시 호출된다.
     * 이벤트 종류(e)로 분기해 티커·캔들 콜백에 전달하고, 구독 응답 등 그 외 메시지는 무시한다.
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        try {
            JsonNode node = objectMapper.readTree(payload);
            String event = node.path("e").asText("");
            if ("24hrTicker".equals(event)) {
                BinanceWsTickerDTO dto = objectMapper.treeToValue(node, BinanceWsTickerDTO.class);
                tickerCallbacks.forEach(cb -> cb.accept(dto));
            } else if ("kline".equals(event)) {
                BinanceWsKlineDTO dto = objectMapper.treeToValue(node, BinanceWsKlineDTO.class);
                klineCallbacks.forEach(cb -> cb.accept(dto));
            } else if ("aggTrade".equals(event)) {
                // 집계 체결 (거래량 급변 감시용)
                BinanceWsAggTradeDTO dto = objectMapper.treeToValue(node, BinanceWsAggTradeDTO.class);
                tradeCallbacks.forEach(cb -> cb.accept(dto));
            }
            // 구독 응답 {"result":null,"id":n} 등은 무시
        } catch (JsonProcessingException e) {
            log.warn(LogMessage.BINANCE_WS_MESSAGE_PARSE_ERROR.getMessage(), e.getMessage());
        }
    }

    /**
     * WebSocket 연결이 끊어졌을 때 호출된다. (24시간 경과 시 서버가 끊는 경우 포함)
     * 구독 상태를 비우고 지수 백오프 방식으로 재연결을 시도한다.
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.warn(LogMessage.BINANCE_WS_CONNECTION_CLOSED.getMessage(), status);
        subscribedSymbols.clear();
        subscribedTradeSymbols.clear();
        if (shouldConnect) {
            scheduleReconnect();
        }
    }

    /**
     * WebSocket 전송 오류 발생 시 호출된다.
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error(LogMessage.BINANCE_WS_TRANSPORT_ERROR.getMessage(), exception.getMessage());
    }

    // ─────────────────────────────────────────────
    // 내부 헬퍼 메서드
    // ─────────────────────────────────────────────

    /**
     * Binance WebSocket 서버에 연결한다.
     * 핸드셰이크는 비동기라 실패 시 afterConnectionClosed가 호출되지 않으므로, future 실패 시 직접 재연결을 예약한다.
     */
    private void connect() {
        try {
            log.info(LogMessage.BINANCE_WS_CONNECTING.getMessage(), websocketUrl);
            webSocketClient.execute(this, null, URI.create(websocketUrl))
                    .whenComplete((s, ex) -> {
                        if (ex != null) onConnectFailed(ex);
                    });
        } catch (Exception e) {
            // 요청 생성 단계의 동기 실패
            onConnectFailed(e);
        }
    }

    /**
     * 연결 실패를 기록하고, 종료 중이 아니면 재연결을 예약한다.
     */
    private void onConnectFailed(Throwable e) {
        log.error(LogMessage.BINANCE_WS_CONNECT_FAILED.getMessage(), e.getMessage());
        if (shouldConnect) {
            scheduleReconnect();
        }
    }

    /**
     * 지수 백오프 방식으로 재연결을 스케줄한다.
     * 대기 시간: reconnectDelay → 2배 → 4배 → ... → reconnectMaxDelay
     */
    private void scheduleReconnect() {
        long delay = currentReconnectDelay;
        currentReconnectDelay = Math.min(currentReconnectDelay * 2, reconnectMaxDelay);
        log.info(LogMessage.BINANCE_WS_RECONNECT_SCHEDULED.getMessage(), delay);
        reconnectScheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
    }

    /** 심볼마다 소문자 ticker·1분봉 스트림 이름을 만든다. (예: btcusdt@ticker, btcusdt@kline_1m) */
    private static List<String> streamsOf(Set<String> symbols) {
        List<String> streams = new ArrayList<>();
        for (String symbol : symbols) {
            String lower = symbol.toLowerCase(Locale.ROOT);
            streams.add(lower + "@ticker");
            streams.add(lower + "@kline_1m");
        }
        return streams;
    }

    /** 심볼마다 소문자 집계 체결 스트림 이름을 만든다. (예: btcusdt@aggTrade) */
    private static List<String> tradeStreamsOf(Set<String> symbols) {
        List<String> streams = new ArrayList<>();
        for (String symbol : symbols) {
            streams.add(symbol.toLowerCase(Locale.ROOT) + "@aggTrade");
        }
        return streams;
    }

    /**
     * WebSocket 세션에 JSON 메시지를 전송한다.
     *
     * @param request 전송할 요청 객체
     * @return 전송했으면 true
     */
    private boolean send(BinanceWsRequest request) {
        WebSocketSession current = session;
        if (current == null || !current.isOpen()) {
            return false;
        }
        try {
            current.sendMessage(new TextMessage(objectMapper.writeValueAsString(request)));
            return true;
        } catch (IOException e) {
            log.error(LogMessage.BINANCE_WS_MESSAGE_SEND_FAILED.getMessage(), e.getMessage());
            return false;
        }
    }
}
