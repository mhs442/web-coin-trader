package com.coin.webcointrader.client.market;

import com.coin.webcointrader.client.market.dto.WebSocketTickerDTO;
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
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

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
        client.setTickerCallback(received::set);

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
        client.setTickerCallback(received::set);

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
    @DisplayName("handleTextMessage: op 메시지(pong 등)는 무시한다")
    void handleTextMessage_ignoresOpMessages() throws Exception {
        // given
        AtomicReference<WebSocketTickerDTO> received = new AtomicReference<>();
        client.setTickerCallback(received::set);

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
}
