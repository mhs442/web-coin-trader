package com.coin.webcointrader.bybit.market;

import com.coin.webcointrader.bybit.market.dto.WebSocketPublicTradeDTO;
import com.coin.webcointrader.bybit.market.dto.WebSocketTickerDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.net.URI;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class BybitWebSocketClientTest {

    private BybitWebSocketClient client;

    @Mock
    private StandardWebSocketClient webSocketClient;

    @Mock
    private WebSocketSession session;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        client = new BybitWebSocketClient(webSocketClient, objectMapper);
        // @Value 필드 직접 설정 (@PostConstruct는 호출하지 않음)
        ReflectionTestUtils.setField(client, "websocketUrl", "ws://localhost:0/mock-ws");
        ReflectionTestUtils.setField(client, "pingInterval", 20000L);
        ReflectionTestUtils.setField(client, "reconnectDelay", 1000L);
        ReflectionTestUtils.setField(client, "reconnectMaxDelay", 30000L);
        ReflectionTestUtils.setField(client, "shouldConnect", false); // 자동 재연결 비활성화
    }

    // ─────────────────────────────────────────────
    // 메시지 파싱
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("handleTextMessage: snapshot 메시지를 파싱하여 콜백을 호출한다")
    void handleTextMessage_parsesSnapshotAndCallsCallback() throws Exception {
        // given
        AtomicReference<WebSocketTickerDTO> received = new AtomicReference<>();
        client.addTickerCallback(received::set);

        // 세션 설정 (메시지 수신에 필요)
        String snapshotJson = """
                {
                  "topic": "tickers.BTCUSDT",
                  "type": "snapshot",
                  "data": {
                    "symbol": "BTCUSDT",
                    "lastPrice": "50000.00",
                    "volume24h": "10000.0"
                  },
                  "ts": 1699999800000
                }
                """;

        // when
        client.handleTextMessage(session, new TextMessage(snapshotJson));

        // then
        assertThat(received.get()).isNotNull();
        assertThat(received.get().getTopic()).isEqualTo("tickers.BTCUSDT");
        assertThat(received.get().getType()).isEqualTo("snapshot");
        assertThat(received.get().getData().getSymbol()).isEqualTo("BTCUSDT");
        assertThat(received.get().getData().getLastPrice()).isEqualTo("50000.00");
    }

    @Test
    @DisplayName("handleTextMessage: delta 메시지를 파싱하여 콜백을 호출한다")
    void handleTextMessage_parsesDeltaAndCallsCallback() throws Exception {
        // given
        AtomicReference<WebSocketTickerDTO> received = new AtomicReference<>();
        client.addTickerCallback(received::set);

        String deltaJson = """
                {
                  "topic": "tickers.BTCUSDT",
                  "type": "delta",
                  "data": {
                    "symbol": "BTCUSDT",
                    "lastPrice": "50100.00"
                  },
                  "ts": 1699999801000
                }
                """;

        // when
        client.handleTextMessage(session, new TextMessage(deltaJson));

        // then
        assertThat(received.get()).isNotNull();
        assertThat(received.get().getType()).isEqualTo("delta");
        assertThat(received.get().getData().getLastPrice()).isEqualTo("50100.00");
    }

    @Test
    @DisplayName("handleTextMessage: publicTrade 메시지를 체결 콜백으로 전달한다")
    void handleTextMessage_publicTradeCallsTradeCallback() throws Exception {
        AtomicReference<WebSocketPublicTradeDTO> received = new AtomicReference<>();
        client.addTradeCallback(received::set);

        client.handleTextMessage(session, new TextMessage("""
                {
                  "topic": "publicTrade.BTCUSDT",
                  "type": "snapshot",
                  "ts": 1672304486868,
                  "data": [
                    { "T": 1672304486865, "s": "BTCUSDT", "S": "Buy", "v": "0.001", "p": "16578.50", "i": "a1", "BT": false },
                    { "T": 1672304486866, "s": "BTCUSDT", "S": "Sell", "v": "0.250", "p": "16578.00", "i": "a2", "BT": false }
                  ]
                }
                """));

        assertThat(received.get().getData()).hasSize(2);
        assertThat(received.get().getData().get(0).getSymbol()).isEqualTo("BTCUSDT");
        assertThat(received.get().getData().get(0).getTradeTime()).isEqualTo(1672304486865L);
        assertThat(received.get().getData().get(1).getQty()).isEqualTo("0.250");
    }

    @Test
    @DisplayName("handleTextMessage: op 메시지(pong 등)는 무시한다")
    void handleTextMessage_ignoresOpMessages() throws Exception {
        // given
        AtomicReference<WebSocketTickerDTO> received = new AtomicReference<>();
        client.addTickerCallback(received::set);

        String pongJson = """
                { "op": "pong", "success": true }
                """;

        // when
        client.handleTextMessage(session, new TextMessage(pongJson));

        // then - 콜백이 호출되지 않음
        assertThat(received.get()).isNull();
    }

    @Test
    @DisplayName("handleTextMessage: 콜백이 없으면 예외 없이 처리한다")
    void handleTextMessage_noCallbackDoesNotThrow() throws Exception {
        // given - 콜백 미설정
        String snapshotJson = """
                {
                  "topic": "tickers.BTCUSDT",
                  "type": "snapshot",
                  "data": { "symbol": "BTCUSDT", "lastPrice": "50000.00" },
                  "ts": 1699999800000
                }
                """;

        // when & then - 예외 없이 처리
        client.handleTextMessage(session, new TextMessage(snapshotJson));
    }

    // ─────────────────────────────────────────────
    // 구독 관리
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("subscribe: 심볼을 구독하면 subscribedSymbols에 추가된다")
    void subscribe_addsToSubscribedSymbols() {
        // given
        ReflectionTestUtils.setField(client, "session", session);
        given(session.isOpen()).willReturn(true);

        // when
        client.subscribe("BTCUSDT");

        // then
        assertThat(client.getSubscribedSymbols()).contains("BTCUSDT");
    }

    @Test
    @DisplayName("subscribe: 이미 구독 중인 심볼은 중복 구독하지 않는다")
    void subscribe_doesNotDuplicateExistingSymbol() throws Exception {
        // given
        ReflectionTestUtils.setField(client, "session", session);
        given(session.isOpen()).willReturn(true);

        client.subscribe("BTCUSDT");

        // when - 같은 심볼 재구독
        client.subscribe("BTCUSDT");

        // then - 메시지는 한 번만 전송
        then(session).should(times(1)).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("unsubscribe: 심볼을 구독 해제하면 subscribedSymbols에서 제거된다")
    void unsubscribe_removesFromSubscribedSymbols() {
        // given
        ReflectionTestUtils.setField(client, "session", session);
        given(session.isOpen()).willReturn(true);

        client.subscribe("BTCUSDT");

        // when
        client.unsubscribe("BTCUSDT");

        // then
        assertThat(client.getSubscribedSymbols()).doesNotContain("BTCUSDT");
    }

    @Test
    @DisplayName("syncSubscriptions: 새 심볼은 구독하고, 불필요한 심볼은 해제한다")
    void syncSubscriptions_addsNewAndRemovesOld() {
        // given
        ReflectionTestUtils.setField(client, "session", session);
        given(session.isOpen()).willReturn(true);

        client.subscribe("BTCUSDT");
        client.subscribe("ETHUSDT");

        // when - BTCUSDT 유지, ETHUSDT 제거, SOLUSDT 추가
        client.syncSubscriptions(Set.of("BTCUSDT", "SOLUSDT"));

        // then
        assertThat(client.getSubscribedSymbols()).containsExactlyInAnyOrder("BTCUSDT", "SOLUSDT");
    }

    @Test
    @DisplayName("syncTradeSubscriptions: 새 심볼은 publicTrade 토픽을 구독하고, 빠진 심볼은 해제한다")
    void syncTradeSubscriptions_addsNewAndRemovesOld() throws Exception {
        ReflectionTestUtils.setField(client, "session", session);
        given(session.isOpen()).willReturn(true);
        client.syncTradeSubscriptions(Set.of("BTCUSDT", "ETHUSDT"));

        client.syncTradeSubscriptions(Set.of("BTCUSDT"));

        assertThat(client.getSubscribedTradeSymbols()).containsExactly("BTCUSDT");
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        then(session).should(atLeastOnce()).sendMessage(captor.capture());
        assertThat(captor.getAllValues()).anyMatch(m -> m.getPayload().contains("\"subscribe\"") && m.getPayload().contains("publicTrade.BTCUSDT"));
        assertThat(captor.getAllValues()).anyMatch(m -> m.getPayload().contains("\"unsubscribe\"") && m.getPayload().contains("publicTrade.ETHUSDT"));
    }

    @Test
    @DisplayName("afterConnectionEstablished: 체결(publicTrade) 구독도 재구독한다")
    void afterConnectionEstablished_resubscribesTradeTopics() throws Exception {
        ReflectionTestUtils.setField(client, "session", session);
        given(session.isOpen()).willReturn(true);
        client.syncTradeSubscriptions(Set.of("BTCUSDT"));
        reset(session);
        given(session.isOpen()).willReturn(true);

        client.afterConnectionEstablished(session);

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        then(session).should(atLeastOnce()).sendMessage(captor.capture());
        assertThat(captor.getAllValues()).anyMatch(m -> m.getPayload().contains("publicTrade.BTCUSDT"));
    }

    // ─────────────────────────────────────────────
    // 연결 이벤트
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("afterConnectionEstablished: 연결 성공 시 기존 구독을 재구독한다")
    void afterConnectionEstablished_resubscribesExistingTopics() throws Exception {
        // given - 사전 구독 상태 설정 (subscribedSymbols에 직접 추가)
        ReflectionTestUtils.setField(client, "session", session);
        given(session.isOpen()).willReturn(true);
        client.subscribe("BTCUSDT");

        // 메시지 카운트 리셋을 위해 mock 리셋
        reset(session);
        given(session.isOpen()).willReturn(true);

        // when - 재연결 시뮬레이션
        client.afterConnectionEstablished(session);

        // then - 재구독 메시지 전송
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        then(session).should(atLeastOnce()).sendMessage(captor.capture());

        // 재구독 메시지에 "subscribe"와 "tickers.BTCUSDT"가 포함되어야 함
        boolean hasResubscribe = captor.getAllValues().stream()
                .anyMatch(msg -> msg.getPayload().contains("subscribe") && msg.getPayload().contains("tickers.BTCUSDT"));
        assertThat(hasResubscribe).isTrue();
    }

    // ─────────────────────────────────────────────
    // 연결 실패·재연결
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("connect: 핸드셰이크가 비동기로 실패하면 재연결을 예약한다")
    void connect_asyncHandshakeFailure_schedulesReconnect() {
        // given - 연결 허용 + 핸드셰이크 실패 future
        ReflectionTestUtils.setField(client, "shouldConnect", true);
        given(webSocketClient.execute(any(WebSocketHandler.class), nullable(WebSocketHttpHeaders.class), any(URI.class)))
                .willReturn(CompletableFuture.failedFuture(new IllegalStateException("handshake failed")));

        try {
            // when
            client.init();

            // then - 재연결 예약 시 다음 대기 시간이 2배로 증가
            assertThat(ReflectionTestUtils.getField(client, "currentReconnectDelay")).isEqualTo(2000L);
        } finally {
            client.destroy();
        }
    }

    @Test
    @DisplayName("connect: 재연결 시도도 실패하면 다시 예약해 재시도를 계속한다")
    void connect_reconnectAttemptFailure_keepsRetrying() {
        // given - 짧은 재연결 대기 + 핸드셰이크 계속 실패
        ReflectionTestUtils.setField(client, "shouldConnect", true);
        ReflectionTestUtils.setField(client, "reconnectDelay", 10L);
        ReflectionTestUtils.setField(client, "reconnectMaxDelay", 20L);
        given(webSocketClient.execute(any(WebSocketHandler.class), nullable(WebSocketHttpHeaders.class), any(URI.class)))
                .willAnswer(inv -> CompletableFuture.failedFuture(new IllegalStateException("handshake failed")));

        try {
            // when
            client.init();

            // then - 최초 1회 + 재연결 시도가 끊기지 않고 반복
            then(webSocketClient).should(timeout(2000).atLeast(3)).execute(any(WebSocketHandler.class), nullable(WebSocketHttpHeaders.class), any(URI.class));
        } finally {
            client.destroy();
        }
    }

    @Test
    @DisplayName("connect: 핸드셰이크에 성공하면 재연결을 예약하지 않는다")
    void connect_handshakeSuccess_doesNotScheduleReconnect() {
        // given
        ReflectionTestUtils.setField(client, "shouldConnect", true);
        given(webSocketClient.execute(any(WebSocketHandler.class), nullable(WebSocketHttpHeaders.class), any(URI.class)))
                .willReturn(CompletableFuture.completedFuture(session));

        try {
            // when
            client.init();

            // then - 대기 시간 변화 없음
            assertThat(ReflectionTestUtils.getField(client, "currentReconnectDelay")).isEqualTo(1000L);
        } finally {
            client.destroy();
        }
    }

    @Test
    @DisplayName("connect: 종료 중(shouldConnect=false)에는 핸드셰이크가 실패해도 재연결하지 않는다")
    void connect_shuttingDown_doesNotScheduleReconnect() {
        // given - setUp 기본값 shouldConnect=false
        given(webSocketClient.execute(any(WebSocketHandler.class), nullable(WebSocketHttpHeaders.class), any(URI.class)))
                .willReturn(CompletableFuture.failedFuture(new IllegalStateException("handshake failed")));

        try {
            // when
            client.init();

            // then
            assertThat(ReflectionTestUtils.getField(client, "currentReconnectDelay")).isEqualTo(1000L);
            then(webSocketClient).should(times(1)).execute(any(WebSocketHandler.class), nullable(WebSocketHttpHeaders.class), any(URI.class));
        } finally {
            client.destroy();
        }
    }
}
