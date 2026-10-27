package com.coin.webcointrader.bybit.stream;

import com.coin.webcointrader.bybit.stream.dto.WebSocketExecutionDTO;
import com.coin.webcointrader.common.util.HmacUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BybitPrivateWebSocketClientTest {

    private static final Long USER_ID = 1L;
    private static final String API_KEY = "test-key";
    private static final String API_SECRET = "test-secret";

    private BybitPrivateWebSocketClient client;

    @Mock
    private StandardWebSocketClient webSocketClient;

    @Mock
    private WebSocketSession session;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        client = new BybitPrivateWebSocketClient(webSocketClient, objectMapper);
        // @Value 필드 직접 설정
        ReflectionTestUtils.setField(client, "privateUrl", "ws://localhost:0/mock-private-ws");
        ReflectionTestUtils.setField(client, "pingInterval", 20000L);
        ReflectionTestUtils.setField(client, "reconnectDelay", 10L);
        ReflectionTestUtils.setField(client, "reconnectMaxDelay", 100L);

        given(webSocketClient.execute(any(WebSocketHandler.class), isNull(WebSocketHttpHeaders.class), any(URI.class)))
                .willReturn(new CompletableFuture<>()); // 핸드셰이크 진행 중 상태로 유지
        given(session.isOpen()).willReturn(true);
    }

    @AfterEach
    void tearDown() {
        client.destroy();
    }

    /** open 후 연결 수립까지 진행한 PrivateConnection을 반환한다. */
    private PrivateConnection openAndConnect() {
        client.open(USER_ID, API_KEY, API_SECRET);
        PrivateConnection conn = client.connection(USER_ID);
        conn.afterConnectionEstablished(session);
        return conn;
    }

    /** 세션으로 보낸 메시지들을 JSON 노드 목록으로 반환한다. */
    private List<JsonNode> sentMessages() throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        then(session).should(atLeast(0)).sendMessage(captor.capture());
        List<JsonNode> nodes = new ArrayList<>();
        for (TextMessage m : captor.getAllValues()) nodes.add(objectMapper.readTree(m.getPayload()));
        return nodes;
    }

    @Test
    @DisplayName("연결 수립 시 auth 메시지를 보낸다 (args=[apiKey, 숫자 expires, HMAC(GET/realtime+expires)])")
    void sendsAuthOnConnect() throws Exception {
        long before = System.currentTimeMillis();

        openAndConnect();

        List<JsonNode> sent = sentMessages();
        assertThat(sent).hasSize(1);
        JsonNode auth = sent.get(0);
        assertThat(auth.get("op").asText()).isEqualTo("auth");
        JsonNode args = auth.get("args");
        assertThat(args.get(0).asText()).isEqualTo(API_KEY);
        assertThat(args.get(1).isNumber()).isTrue();
        long expires = args.get(1).asLong();
        assertThat(expires).isGreaterThan(before);
        assertThat(args.get(2).asText()).isEqualTo(HmacUtil.sha256Hex(API_SECRET, "GET/realtime" + expires));
    }

    @Test
    @DisplayName("인증 성공 응답을 받으면 execution.linear 토픽을 구독한다")
    void subscribesExecutionAfterAuthSuccess() throws Exception {
        PrivateConnection conn = openAndConnect();

        conn.handleTextMessage(session, new TextMessage(
                "{\"success\":true,\"ret_msg\":\"\",\"op\":\"auth\",\"conn_id\":\"c1\"}"));

        List<JsonNode> sent = sentMessages();
        assertThat(sent).hasSize(2);
        assertThat(sent.get(1).get("op").asText()).isEqualTo("subscribe");
        assertThat(sent.get(1).get("args").get(0).asText()).isEqualTo("execution.linear");
    }

    @Test
    @DisplayName("인증 실패 응답을 받으면 세션을 닫고 재연결하지 않는다")
    void authFailure_closesWithoutReconnect() throws Exception {
        PrivateConnection conn = openAndConnect();

        conn.handleTextMessage(session, new TextMessage(
                "{\"success\":false,\"ret_msg\":\"invalid sign\",\"op\":\"auth\",\"conn_id\":\"c1\"}"));
        conn.afterConnectionClosed(session, CloseStatus.NORMAL);
        Thread.sleep(200); // 재연결 지연(10ms)보다 충분히 대기

        then(session).should().close();
        assertThat(client.getConnectedUserIds()).doesNotContain(USER_ID);
        then(webSocketClient).should(times(1)).execute(any(WebSocketHandler.class), isNull(WebSocketHttpHeaders.class), any(URI.class));
        // 구독 요청은 보내지 않음
        assertThat(sentMessages()).hasSize(1);
    }

    @Test
    @DisplayName("execution 메시지의 체결 건마다 (userId, data) 콜백을 호출한다 (부분 체결 2건 → 2회)")
    void parsesExecutionAndCallsCallbackPerFill() throws Exception {
        List<Long> userIds = new ArrayList<>();
        List<WebSocketExecutionDTO.ExecutionData> fills = new ArrayList<>();
        client.addExecutionCallback((userId, data) -> {
            userIds.add(userId);
            fills.add(data);
        });
        PrivateConnection conn = openAndConnect();

        String payload = """
                {
                  "id": "592324803b2785-26fa-4214-9963-bdd4727f07be",
                  "topic": "execution.linear",
                  "creationTime": 1672364174455,
                  "data": [
                    {
                      "category": "linear", "symbol": "BTCUSDT", "orderId": "o-1", "orderLinkId": "q12-E-1760950000000",
                      "execId": "e-1", "side": "Buy", "execPrice": "50000.5", "execQty": "0.010", "execFee": "0.3",
                      "execType": "Trade", "execPnl": "0", "closedSize": "0", "leavesQty": "0.010", "orderQty": "0.020",
                      "createType": "CreateByUser", "execTime": "1672364174443", "isMaker": false
                    },
                    {
                      "category": "linear", "symbol": "BTCUSDT", "orderId": "o-1", "orderLinkId": "q12-E-1760950000000",
                      "execId": "e-2", "side": "Buy", "execPrice": "50001.0", "execQty": "0.010", "execFee": "0.3",
                      "execType": "Trade", "execPnl": "0", "closedSize": "0", "leavesQty": "0", "orderQty": "0.020",
                      "createType": "CreateByUser", "stopOrderType": "", "execTime": "1672364174450", "isMaker": false
                    }
                  ]
                }
                """;
        conn.handleTextMessage(session, new TextMessage(payload));

        assertThat(userIds).containsExactly(USER_ID, USER_ID);
        assertThat(fills).hasSize(2);
        WebSocketExecutionDTO.ExecutionData first = fills.get(0);
        assertThat(first.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(first.getOrderId()).isEqualTo("o-1");
        assertThat(first.getOrderLinkId()).isEqualTo("q12-E-1760950000000");
        assertThat(first.getExecId()).isEqualTo("e-1");
        assertThat(first.getSide()).isEqualTo("Buy");
        assertThat(first.getExecPrice()).isEqualTo("50000.5");
        assertThat(first.getExecQty()).isEqualTo("0.010");
        assertThat(first.getExecFee()).isEqualTo("0.3");
        assertThat(first.getExecType()).isEqualTo("Trade");
        assertThat(first.getExecPnl()).isEqualTo("0");
        assertThat(first.getClosedSize()).isEqualTo("0");
        assertThat(first.getLeavesQty()).isEqualTo("0.010");
        assertThat(first.getOrderQty()).isEqualTo("0.020");
        assertThat(first.getCreateType()).isEqualTo("CreateByUser");
        assertThat(first.getExecTime()).isEqualTo("1672364174443");
        assertThat(fills.get(1).getExecId()).isEqualTo("e-2");
    }

    @Test
    @DisplayName("콜백에서 예외가 나도 다음 체결 건 처리를 계속한다 (WS 수신 스레드 보호)")
    void callbackException_doesNotStopOtherFills() throws Exception {
        List<String> execIds = new ArrayList<>();
        client.addExecutionCallback((userId, data) -> {
            if ("e-1".equals(data.getExecId())) throw new IllegalStateException("boom");
            execIds.add(data.getExecId());
        });
        PrivateConnection conn = openAndConnect();

        conn.handleTextMessage(session, new TextMessage(
                "{\"topic\":\"execution.linear\",\"data\":[{\"execId\":\"e-1\"},{\"execId\":\"e-2\"}]}"));

        assertThat(execIds).containsExactly("e-2");
    }

    @Test
    @DisplayName("pong·subscribe 응답(op 메시지)은 콜백 없이 무시한다")
    void ignoresControlMessages() throws Exception {
        List<Object> received = new ArrayList<>();
        client.addExecutionCallback((userId, data) -> received.add(data));
        PrivateConnection conn = openAndConnect();

        conn.handleTextMessage(session, new TextMessage(
                "{\"op\":\"pong\",\"args\":[\"1675418560633\"],\"conn_id\":\"c1\"}"));
        conn.handleTextMessage(session, new TextMessage(
                "{\"success\":true,\"ret_msg\":\"\",\"op\":\"subscribe\",\"conn_id\":\"c1\"}"));

        assertThat(received).isEmpty();
        assertThat(sentMessages()).hasSize(1); // auth만 전송
    }

    @Test
    @DisplayName("같은 사용자로 open을 두 번 호출해도 연결은 1개만 만든다")
    void openTwice_createsSingleConnection() {
        client.open(USER_ID, API_KEY, API_SECRET);
        client.open(USER_ID, API_KEY, API_SECRET);

        then(webSocketClient).should(times(1)).execute(any(WebSocketHandler.class), isNull(WebSocketHttpHeaders.class), any(URI.class));
        assertThat(client.getConnectedUserIds()).containsExactly(USER_ID);
    }

    @Test
    @DisplayName("close 후 연결이 끊겨도 재연결하지 않는다")
    void close_closesSessionAndDoesNotReconnect() throws Exception {
        PrivateConnection conn = openAndConnect();

        client.close(USER_ID);
        conn.afterConnectionClosed(session, CloseStatus.NORMAL);
        Thread.sleep(200);

        then(session).should().close();
        assertThat(client.getConnectedUserIds()).isEmpty();
        then(webSocketClient).should(times(1)).execute(any(WebSocketHandler.class), isNull(WebSocketHttpHeaders.class), any(URI.class));
    }

    @Test
    @DisplayName("구독 성공 응답을 받아야 체결 수신 준비(ready) 상태가 된다")
    void ready_afterSubscribeSuccess() {
        PrivateConnection conn = openAndConnect();
        conn.handleTextMessage(session, new TextMessage("{\"success\":true,\"ret_msg\":\"\",\"op\":\"auth\"}"));
        assertThat(client.isReady(USER_ID)).isFalse(); // 인증만으로는 준비 아님

        conn.handleTextMessage(session, new TextMessage("{\"success\":true,\"ret_msg\":\"\",\"op\":\"subscribe\"}"));

        assertThat(client.isReady(USER_ID)).isTrue();
    }

    @Test
    @DisplayName("연결이 끊기면 ready가 해제된다 (재연결·재구독 전까지 WS 대기 안 함)")
    void notReady_afterConnectionClosed() {
        PrivateConnection conn = openAndConnect();
        conn.handleTextMessage(session, new TextMessage("{\"success\":true,\"ret_msg\":\"\",\"op\":\"auth\"}"));
        conn.handleTextMessage(session, new TextMessage("{\"success\":true,\"ret_msg\":\"\",\"op\":\"subscribe\"}"));

        conn.afterConnectionClosed(session, CloseStatus.SERVER_ERROR);

        assertThat(client.isReady(USER_ID)).isFalse();
        assertThat(client.isReady(99L)).isFalse(); // 연결 없는 사용자
    }

    @Test
    @DisplayName("close하지 않은 연결이 끊기면 재연결을 시도한다 (재연결 시 다시 인증)")
    void unexpectedClose_reconnects() {
        PrivateConnection conn = openAndConnect();

        conn.afterConnectionClosed(session, CloseStatus.SERVER_ERROR);

        then(webSocketClient).should(timeout(1000).times(2))
                .execute(any(WebSocketHandler.class), isNull(WebSocketHttpHeaders.class), any(URI.class));
        assertThat(client.getConnectedUserIds()).containsExactly(USER_ID);
    }

    @Test
    @DisplayName("핸드셰이크가 비동기로 실패하면 재연결을 시도한다")
    void handshakeFailure_reconnects() {
        given(webSocketClient.execute(any(WebSocketHandler.class), isNull(WebSocketHttpHeaders.class), any(URI.class)))
                .willReturn(CompletableFuture.failedFuture(new IllegalStateException("handshake failed")))
                .willReturn(new CompletableFuture<>());

        client.open(USER_ID, API_KEY, API_SECRET);

        then(webSocketClient).should(timeout(1000).times(2))
                .execute(any(WebSocketHandler.class), isNull(WebSocketHttpHeaders.class), any(URI.class));
    }
}
