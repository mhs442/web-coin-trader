package com.coin.webcointrader.binance.stream;

import com.coin.webcointrader.binance.common.BinanceApiException;
import com.coin.webcointrader.binance.stream.dto.BinanceOrderTradeUpdateDTO;
import com.coin.webcointrader.common.enums.LogMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;

/**
 * 사용자 1명분의 Binance user data stream 연결.
 * 흐름: listenKey 발급(REST) → 개인 스트림 접속 → 주기적 연장(keepalive) → ORDER_TRADE_UPDATE 수신 시 콜백 전달.
 * 끊기거나 listenKey 가 만료되면 지수 백오프로 재접속하며, 재접속마다 listenKey 를 다시 발급받는다.
 * 키 오류(-2014/-2015)나 close() 이후에는 재접속하지 않는다.
 * (Binance 는 접속 후 별도 인증·구독 메시지가 없고, ping 은 서버가 보내며 컨테이너가 자동으로 pong 응답한다)
 */
@Slf4j
class BinancePrivateConnection extends TextWebSocketHandler {

    private static final String ORDER_TRADE_UPDATE = "ORDER_TRADE_UPDATE"; // 주문·체결 이벤트
    private static final String LISTEN_KEY_EXPIRED = "listenKeyExpired";   // listenKey 만료 이벤트
    private static final int LISTEN_KEY_NOT_EXIST = -1125;                 // 연장 대상 listenKey 없음 → 재발급 필요
    private static final Set<Integer> INVALID_KEY_CODES = Set.of(-2014, -2015); // API 키 형식 오류 / 키·IP·권한 거부

    @Getter
    private final Long userId;                  // 사용자 ID
    @Getter(lombok.AccessLevel.PACKAGE)
    private final String apiKey;                // API Key (listenKey 발급·연장용, 로그 출력 금지)
    private final BinancePrivateWebSocketClient owner; // 연결 관리자 (설정·스케줄러·콜백 보유)

    private volatile WebSocketSession session;  // 현재 WebSocket 세션
    private volatile boolean stopped;           // 종료 여부 (true면 재접속하지 않음)
    @Getter
    private volatile boolean ready;             // 체결 수신 준비 여부 (접속 성공 ~ 연결 종료 구간만 true)
    private volatile long currentReconnectDelay; // 현재 재접속 대기 시간 (지수 백오프)
    private ScheduledFuture<?> keepAliveTask;   // listenKey 연장 작업

    BinancePrivateConnection(Long userId, String apiKey, BinancePrivateWebSocketClient owner) {
        this.userId = userId;
        this.apiKey = apiKey;
        this.owner = owner;
        this.currentReconnectDelay = owner.reconnectDelay();
    }

    /**
     * 접속을 스케줄러 스레드에서 시작한다. (listenKey 발급이 블로킹 REST 호출이라 호출 스레드를 막지 않도록)
     */
    void connect() {
        try {
            owner.schedule(this::doConnect, 0);
        } catch (RejectedExecutionException e) {
            // 애플리케이션 종료 중 — 접속하지 않음
        }
    }

    /**
     * 연결을 종료한다. 이후 재접속하지 않는다.
     */
    void shutdown() {
        stopped = true;
        ready = false;
        stopKeepAlive();
        closeSession();
    }

    /**
     * listenKey 유효기간(60분)을 연장한다. (스케줄러가 주기적으로 호출)
     * 키가 없어졌으면(-1125) 세션을 닫아 재발급·재접속 흐름으로 넘기고, 그 외 오류는 다음 주기에 다시 시도한다.
     */
    void keepAlive() {
        if (stopped) return;
        try {
            owner.keepAliveListenKey(apiKey);
        } catch (BinanceApiException e) {
            log.warn(LogMessage.BINANCE_PRIVATE_WS_KEEPALIVE_FAILED.getMessage(), userId, e.getMessage());
            if (e.getCode() == LISTEN_KEY_NOT_EXIST) {
                closeForReconnect();
            }
        } catch (Exception e) {
            // 일시 오류 — 유효기간 60분 중 연장 주기(30분)만큼 여유가 있어 다음 주기에 재시도
            log.warn(LogMessage.BINANCE_PRIVATE_WS_KEEPALIVE_FAILED.getMessage(), userId, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    // WebSocket 이벤트 핸들러
    // ─────────────────────────────────────────────

    /**
     * 접속 성공 시 바로 수신 준비 상태가 되고 listenKey 연장을 시작한다. (인증·구독 단계 없음)
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        this.session = session;
        // 핸드셰이크 중 close()된 경우 바로 닫음
        if (stopped) {
            closeSession();
            return;
        }
        log.info(LogMessage.BINANCE_PRIVATE_WS_CONNECTED.getMessage(), userId);
        currentReconnectDelay = owner.reconnectDelay();
        ready = true;
        startKeepAlive();
    }

    /**
     * 수신 메시지를 처리한다.
     * stream 모드({stream, data})로 감싸 오는 경우 data 를 꺼내고, 이벤트 유형(e)으로 분기한다.
     * ORDER_TRADE_UPDATE → 콜백 전달 / listenKeyExpired → 재발급·재접속 / 그 외(ACCOUNT_UPDATE 등) → 무시
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonNode node = owner.objectMapper().readTree(message.getPayload());
            // 접속 주소를 stream 모드 형식으로 교체한 경우 대비
            if (node.has("stream") && node.has("data")) {
                node = node.get("data");
            }

            String eventType = node.path("e").asText();
            if (ORDER_TRADE_UPDATE.equals(eventType)) {
                BinanceOrderTradeUpdateDTO dto = owner.objectMapper().treeToValue(node, BinanceOrderTradeUpdateDTO.class);
                BinanceOrderTradeUpdateDTO.OrderUpdate o = dto.getOrder();
                if (o == null) return;
                log.debug(LogMessage.BINANCE_PRIVATE_WS_ORDER_UPDATE_RECEIVED.getMessage(), userId, o.getSymbol(),
                        o.getClientOrderId(), o.getExecType(), o.getOrderStatus(), o.getLastFilledPrice(),
                        o.getLastFilledQty(), o.getCommission(), o.getCommissionAsset());
                owner.dispatch(userId, o);
            } else if (LISTEN_KEY_EXPIRED.equals(eventType)) {
                // 만료된 키로는 더 이상 이벤트가 오지 않음 → 끊고 재발급받아 재접속
                log.warn(LogMessage.BINANCE_PRIVATE_WS_LISTEN_KEY_EXPIRED.getMessage(), userId);
                closeForReconnect();
            }
        } catch (JsonProcessingException e) {
            log.warn(LogMessage.BINANCE_WS_MESSAGE_PARSE_ERROR.getMessage(), e.getMessage());
        }
    }

    /**
     * 연결 종료 시 연장을 멈추고, 종료 요청이 없었던 연결이면 재접속을 예약한다. (24시간 접속 제한 포함)
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.warn(LogMessage.BINANCE_PRIVATE_WS_CONNECTION_CLOSED.getMessage(), userId, status);
        // 재접속 전까지 놓치는 체결이 있으므로 WS 대기 대상에서 제외
        ready = false;
        stopKeepAlive();
        scheduleReconnect();
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error(LogMessage.BINANCE_PRIVATE_WS_TRANSPORT_ERROR.getMessage(), userId, exception.getMessage());
    }

    // ─────────────────────────────────────────────
    // 내부 헬퍼 메서드
    // ─────────────────────────────────────────────

    /**
     * listenKey 를 발급받아 비동기 핸드셰이크를 시작한다. (스케줄러 스레드에서 실행)
     * 키 오류 → 관리 대상에서 제거(재접속 안 함) / 그 외 발급 실패·핸드셰이크 실패 → 재접속 예약.
     * 핸드셰이크 실패는 afterConnectionClosed 가 호출되지 않으므로 future 실패 시 직접 재접속을 예약한다.
     */
    private void doConnect() {
        if (stopped) return;
        log.info(LogMessage.BINANCE_PRIVATE_WS_CONNECTING.getMessage(), userId);

        String listenKey;
        try {
            listenKey = owner.createListenKey(apiKey);
        } catch (BinanceApiException e) {
            if (INVALID_KEY_CODES.contains(e.getCode())) {
                // 잘못된 키로 무한 재시도하지 않도록 관리 대상에서 제거 후 종료
                log.warn(LogMessage.BINANCE_PRIVATE_WS_INVALID_KEY.getMessage(), userId, e.getMessage());
                owner.remove(this);
                shutdown();
                return;
            }
            log.error(LogMessage.BINANCE_PRIVATE_WS_LISTEN_KEY_FAILED.getMessage(), userId, e.getMessage());
            scheduleReconnect();
            return;
        } catch (Exception e) {
            log.error(LogMessage.BINANCE_PRIVATE_WS_LISTEN_KEY_FAILED.getMessage(), userId, e.getMessage());
            scheduleReconnect();
            return;
        }

        try {
            owner.execute(this, listenKey).whenComplete((s, ex) -> {
                if (ex != null) {
                    log.error(LogMessage.BINANCE_PRIVATE_WS_CONNECT_FAILED.getMessage(), userId, ex.getMessage());
                    scheduleReconnect();
                }
            });
        } catch (Exception e) {
            log.error(LogMessage.BINANCE_PRIVATE_WS_CONNECT_FAILED.getMessage(), userId, e.getMessage());
            scheduleReconnect();
        }
    }

    /**
     * 세션을 닫아 afterConnectionClosed → 재발급·재접속 흐름으로 넘긴다.
     * 닫힘 콜백 전에도 WS 대기를 하지 않도록 수신 준비 상태를 먼저 해제한다.
     */
    private void closeForReconnect() {
        ready = false;
        closeSession();
    }

    /**
     * 지수 백오프로 재접속을 예약한다. 종료됐거나 관리 대상에서 빠진 연결은 예약하지 않는다.
     * 대기 시간: reconnectDelay → 2배 → 4배 → ... → reconnectMaxDelay
     */
    private void scheduleReconnect() {
        if (stopped || !owner.isActive(this)) return;

        long delay = currentReconnectDelay;
        currentReconnectDelay = Math.min(currentReconnectDelay * 2, owner.reconnectMaxDelay());
        log.info(LogMessage.BINANCE_PRIVATE_WS_RECONNECT_SCHEDULED.getMessage(), userId, delay);
        try {
            owner.schedule(this::doConnect, delay);
        } catch (RejectedExecutionException e) {
            // 애플리케이션 종료 중 — 재접속하지 않음
        }
    }

    private synchronized void startKeepAlive() {
        stopKeepAlive();
        try {
            keepAliveTask = owner.scheduleKeepAlive(this::keepAlive);
        } catch (RejectedExecutionException e) {
            // 애플리케이션 종료 중 — 연장 불필요
        }
    }

    private synchronized void stopKeepAlive() {
        if (keepAliveTask != null) {
            keepAliveTask.cancel(false);
            keepAliveTask = null;
        }
    }

    private void closeSession() {
        WebSocketSession s = session;
        if (s != null && s.isOpen()) {
            try {
                s.close();
            } catch (IOException e) {
                log.warn(LogMessage.BINANCE_WS_SESSION_CLOSE_ERROR.getMessage(), e.getMessage());
            }
        }
    }
}
