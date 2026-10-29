package com.coin.webcointrader.binance.stream;

import com.coin.webcointrader.binance.common.BinanceApiException;
import com.coin.webcointrader.binance.stream.dto.BinanceListenKeyResponse;
import com.coin.webcointrader.binance.stream.dto.BinanceOrderTradeUpdateDTO;
import com.coin.webcointrader.common.util.UserApiKeyContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.*;

/**
 * Binance user data stream(개인 스트림) 연결 관리자 단위 테스트.
 * listenKey 발급 → /private 접속 → ORDER_TRADE_UPDATE 콜백 전달, 연장(keepalive)·만료·재접속·종료를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BinancePrivateWebSocketClientTest {

    private static final Long USER_ID = 1L;
    private static final String API_KEY = "test-key";
    private static final String LISTEN_KEY = "lk-1";

    private BinancePrivateWebSocketClient client;

    @Mock
    private StandardWebSocketClient webSocketClient;

    @Mock
    private BinanceUserStreamFeignClient userStreamFeignClient;

    @Mock
    private WebSocketSession session;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        client = new BinancePrivateWebSocketClient(webSocketClient, objectMapper, userStreamFeignClient);
        // @Value 필드 직접 설정
        ReflectionTestUtils.setField(client, "privateUrl", "ws://localhost:0/private/ws/{listenKey}");
        ReflectionTestUtils.setField(client, "listenKeyKeepAlive", 1_800_000L);
        ReflectionTestUtils.setField(client, "reconnectDelay", 10L);
        ReflectionTestUtils.setField(client, "reconnectMaxDelay", 100L);

        given(userStreamFeignClient.createListenKey()).willReturn(listenKey(LISTEN_KEY));
        given(webSocketClient.execute(any(WebSocketHandler.class), isNull(WebSocketHttpHeaders.class), any(URI.class)))
                .willReturn(new CompletableFuture<>()); // 핸드셰이크 진행 중 상태로 유지
        given(session.isOpen()).willReturn(true);
    }

    @AfterEach
    void tearDown() {
        client.destroy();
        UserApiKeyContext.clear();
    }

    /** open 후 핸드셰이크 시작까지 기다리고 연결 수립까지 진행한 연결을 반환한다. */
    private BinancePrivateConnection openAndConnect() {
        client.open(USER_ID, API_KEY);
        verifyHandshakes(1);
        BinancePrivateConnection conn = client.connection(USER_ID);
        conn.afterConnectionEstablished(session);
        return conn;
    }

    /** 핸드셰이크(WebSocket 접속 시도) 횟수를 검증한다. (접속은 스케줄러 스레드에서 비동기 실행) */
    private void verifyHandshakes(int times) {
        then(webSocketClient).should(timeout(1000).times(times))
                .execute(any(WebSocketHandler.class), isNull(WebSocketHttpHeaders.class), any(URI.class));
    }

    private static BinanceListenKeyResponse listenKey(String key) {
        BinanceListenKeyResponse response = new BinanceListenKeyResponse();
        response.setListenKey(key);
        return response;
    }

    // ─── 접속 ───

    @Test
    @DisplayName("open: 사용자 키로 listenKey 를 발급받아 접속 주소의 {listenKey} 자리에 넣어 접속한다")
    void open_issuesListenKeyAndConnects() {
        AtomicReference<String> keyDuringCall = new AtomicReference<>();
        given(userStreamFeignClient.createListenKey()).willAnswer(inv -> {
            keyDuringCall.set(UserApiKeyContext.getApiKey());
            return listenKey(LISTEN_KEY);
        });

        client.open(USER_ID, API_KEY);

        then(webSocketClient).should(timeout(1000))
                .execute(any(WebSocketHandler.class), isNull(WebSocketHttpHeaders.class),
                        eq(URI.create("ws://localhost:0/private/ws/" + LISTEN_KEY)));
        // 발급 요청에는 해당 사용자의 키가 바인딩됨
        assertThat(keyDuringCall.get()).isEqualTo(API_KEY);
    }

    @Test
    @DisplayName("연결이 수립되면 체결 수신 준비(ready) 상태가 되고, 끊기면 해제된다")
    void ready_whileConnected() {
        client.open(USER_ID, API_KEY);
        verifyHandshakes(1);
        assertThat(client.isReady(USER_ID)).isFalse(); // 핸드셰이크 중

        BinancePrivateConnection conn = client.connection(USER_ID);
        conn.afterConnectionEstablished(session);
        assertThat(client.isReady(USER_ID)).isTrue();

        conn.afterConnectionClosed(session, CloseStatus.SERVER_ERROR);
        assertThat(client.isReady(USER_ID)).isFalse();
        assertThat(client.isReady(99L)).isFalse(); // 연결 없는 사용자
    }

    @Test
    @DisplayName("같은 사용자로 open을 두 번 호출해도 연결은 1개만 만든다")
    void openTwice_createsSingleConnection() throws Exception {
        client.open(USER_ID, API_KEY);
        client.open(USER_ID, API_KEY);

        verifyHandshakes(1);
        Thread.sleep(100);
        verifyHandshakes(1);
        assertThat(client.getConnectedUserIds()).containsExactly(USER_ID);
    }

    @Test
    @DisplayName("listenKey 발급이 키 오류(-2015)로 거부되면 접속하지 않고 재시도하지 않는다")
    void invalidKey_doesNotRetry() throws Exception {
        given(userStreamFeignClient.createListenKey())
                .willThrow(new BinanceApiException(-2015, "Invalid API-key, IP, or permissions for action."));

        client.open(USER_ID, API_KEY);

        then(userStreamFeignClient).should(timeout(1000)).createListenKey();
        Thread.sleep(200); // 재연결 지연(10ms)보다 충분히 대기
        then(userStreamFeignClient).should(times(1)).createListenKey();
        then(webSocketClient).shouldHaveNoInteractions();
        assertThat(client.getConnectedUserIds()).doesNotContain(USER_ID);
    }

    @Test
    @DisplayName("listenKey 발급이 일시 오류로 실패하면 재시도한다")
    void issueFailure_retries() {
        given(userStreamFeignClient.createListenKey())
                .willThrow(new BinanceApiException(-1001, "Internal error; unable to process your request."))
                .willReturn(listenKey(LISTEN_KEY));

        client.open(USER_ID, API_KEY);

        verifyHandshakes(1);
        then(userStreamFeignClient).should(times(2)).createListenKey();
    }

    @Test
    @DisplayName("핸드셰이크가 비동기로 실패하면 재접속한다")
    void handshakeFailure_reconnects() {
        given(webSocketClient.execute(any(WebSocketHandler.class), isNull(WebSocketHttpHeaders.class), any(URI.class)))
                .willReturn(CompletableFuture.failedFuture(new IllegalStateException("handshake failed")))
                .willReturn(new CompletableFuture<>());

        client.open(USER_ID, API_KEY);

        verifyHandshakes(2);
    }

    @Test
    @DisplayName("close하지 않은 연결이 끊기면(24시간 제한 포함) listenKey 를 다시 발급받아 재접속한다")
    void unexpectedClose_reissuesAndReconnects() {
        BinancePrivateConnection conn = openAndConnect();

        conn.afterConnectionClosed(session, CloseStatus.SERVER_ERROR);

        verifyHandshakes(2);
        then(userStreamFeignClient).should(times(2)).createListenKey();
        assertThat(client.getConnectedUserIds()).containsExactly(USER_ID);
    }

    @Test
    @DisplayName("close 후에는 세션을 닫고 listenKey 를 폐기하며 재접속하지 않는다")
    void close_closesAndDeletesListenKey() throws Exception {
        BinancePrivateConnection conn = openAndConnect();

        client.close(USER_ID);
        conn.afterConnectionClosed(session, CloseStatus.NORMAL);

        then(session).should().close();
        then(userStreamFeignClient).should(timeout(1000)).closeListenKey();
        Thread.sleep(200);
        verifyHandshakes(1);
        assertThat(client.getConnectedUserIds()).isEmpty();
    }

    // ─── listenKey 연장 / 만료 ───

    @Test
    @DisplayName("keepAlive: 사용자 키로 PUT 연장을 보낸다")
    void keepAlive_extendsListenKey() throws Exception {
        AtomicReference<String> keyDuringCall = new AtomicReference<>();
        given(userStreamFeignClient.keepAliveListenKey()).willAnswer(inv -> {
            keyDuringCall.set(UserApiKeyContext.getApiKey());
            return listenKey(LISTEN_KEY);
        });
        BinancePrivateConnection conn = openAndConnect();

        conn.keepAlive();

        then(userStreamFeignClient).should().keepAliveListenKey();
        assertThat(keyDuringCall.get()).isEqualTo(API_KEY);
        then(session).should(never()).close();
    }

    @Test
    @DisplayName("keepAlive: 키가 없어졌으면(-1125) 세션을 닫아 재발급·재접속 흐름으로 넘긴다")
    void keepAlive_notExist_closesForReconnect() throws Exception {
        given(userStreamFeignClient.keepAliveListenKey())
                .willThrow(new BinanceApiException(-1125, "This listenKey does not exist."));
        BinancePrivateConnection conn = openAndConnect();

        conn.keepAlive();

        then(session).should().close();
        assertThat(client.isReady(USER_ID)).isFalse();
    }

    @Test
    @DisplayName("keepAlive: 일시 오류는 연결을 유지한다 (다음 주기에 재시도, 유효기간 60분 중 30분 여유)")
    void keepAlive_transientFailure_keepsConnection() throws Exception {
        given(userStreamFeignClient.keepAliveListenKey())
                .willThrow(new BinanceApiException(-1001, "Internal error"));
        BinancePrivateConnection conn = openAndConnect();

        conn.keepAlive();

        then(session).should(never()).close();
        assertThat(client.isReady(USER_ID)).isTrue();
    }

    @Test
    @DisplayName("listenKeyExpired 이벤트를 받으면 세션을 닫아 재발급·재접속 흐름으로 넘긴다")
    void listenKeyExpired_closesForReconnect() throws Exception {
        BinancePrivateConnection conn = openAndConnect();

        conn.handleTextMessage(session, new TextMessage(
                "{\"e\":\"listenKeyExpired\",\"E\":1576653824250,\"listenKey\":\"lk-1\"}"));

        then(session).should().close();
        assertThat(client.isReady(USER_ID)).isFalse();
    }

    // ─── 이벤트 수신 ───

    @Test
    @DisplayName("ORDER_TRADE_UPDATE 를 파싱해 (userId, 주문 정보) 콜백을 호출한다")
    void parsesOrderTradeUpdate() throws Exception {
        List<Long> userIds = new ArrayList<>();
        List<BinanceOrderTradeUpdateDTO.OrderUpdate> updates = new ArrayList<>();
        client.addOrderUpdateCallback((userId, order) -> {
            userIds.add(userId);
            updates.add(order);
        });
        BinancePrivateConnection conn = openAndConnect();

        conn.handleTextMessage(session, new TextMessage(orderTradeUpdate("t-1")));

        assertThat(userIds).containsExactly(USER_ID);
        BinanceOrderTradeUpdateDTO.OrderUpdate o = updates.get(0);
        assertThat(o.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(o.getClientOrderId()).isEqualTo("q12-L-1760950000000");
        assertThat(o.getSide()).isEqualTo("SELL");
        assertThat(o.getOrderType()).isEqualTo("MARKET");
        assertThat(o.getOrigType()).isEqualTo("MARKET");
        assertThat(o.getOrigQty()).isEqualTo("0.020");
        assertThat(o.getExecType()).isEqualTo("TRADE");
        assertThat(o.getOrderStatus()).isEqualTo("FILLED");
        assertThat(o.getOrderId()).isEqualTo("8886774");
        assertThat(o.getLastFilledQty()).isEqualTo("0.020");
        assertThat(o.getCumFilledQty()).isEqualTo("0.020");
        assertThat(o.getLastFilledPrice()).isEqualTo("50000.5");
        assertThat(o.getCommission()).isEqualTo("0.50000500");
        assertThat(o.getCommissionAsset()).isEqualTo("USDT");
        assertThat(o.getTradeTime()).isEqualTo(1568879465650L);
        assertThat(o.getTradeId()).isEqualTo("t-1");
        assertThat(o.isReduceOnly()).isTrue();
        assertThat(o.isClosePosition()).isFalse();
        assertThat(o.getRealizedProfit()).isEqualTo("12.5");
        assertThat(o.getPositionSide()).isEqualTo("BOTH");
    }

    @Test
    @DisplayName("stream 모드 형식({stream, data})으로 감싼 이벤트도 같은 방식으로 처리한다 (접속 주소 교체 대비)")
    void parsesStreamWrappedEvent() throws Exception {
        List<String> tradeIds = new ArrayList<>();
        client.addOrderUpdateCallback((userId, order) -> tradeIds.add(order.getTradeId()));
        BinancePrivateConnection conn = openAndConnect();

        conn.handleTextMessage(session, new TextMessage(
                "{\"stream\":\"lk-1\",\"data\":" + orderTradeUpdate("t-9") + "}"));

        assertThat(tradeIds).containsExactly("t-9");
    }

    @Test
    @DisplayName("ACCOUNT_UPDATE 등 다른 이벤트는 콜백 없이 무시한다")
    void ignoresOtherEvents() throws Exception {
        List<Object> received = new ArrayList<>();
        client.addOrderUpdateCallback((userId, order) -> received.add(order));
        BinancePrivateConnection conn = openAndConnect();

        conn.handleTextMessage(session, new TextMessage(
                "{\"e\":\"ACCOUNT_UPDATE\",\"E\":1564745798939,\"T\":1564745798938,\"a\":{\"m\":\"ORDER\",\"B\":[],\"P\":[]}}"));
        conn.handleTextMessage(session, new TextMessage("not-json"));

        assertThat(received).isEmpty();
        then(session).should(never()).close();
    }

    @Test
    @DisplayName("콜백에서 예외가 나도 다음 콜백 처리를 계속한다 (WS 수신 스레드 보호)")
    void callbackException_doesNotStopOtherCallbacks() throws Exception {
        List<String> tradeIds = new ArrayList<>();
        client.addOrderUpdateCallback((userId, order) -> {
            throw new IllegalStateException("boom");
        });
        client.addOrderUpdateCallback((userId, order) -> tradeIds.add(order.getTradeId()));
        BinancePrivateConnection conn = openAndConnect();

        conn.handleTextMessage(session, new TextMessage(orderTradeUpdate("t-1")));

        assertThat(tradeIds).containsExactly("t-1");
    }

    /** 공식 예시 형식의 ORDER_TRADE_UPDATE (청산 시장가 전량 체결) */
    private static String orderTradeUpdate(String tradeId) {
        return """
                {"e":"ORDER_TRADE_UPDATE","E":1568879465651,"T":1568879465650,
                 "o":{"s":"BTCUSDT","c":"q12-L-1760950000000","S":"SELL","o":"MARKET","f":"GTC",
                  "q":"0.020","p":"0","ap":"50000.5","sp":"0","x":"TRADE","X":"FILLED",
                  "i":8886774,"l":"0.020","z":"0.020","L":"50000.5","N":"USDT","n":"0.50000500",
                  "T":1568879465650,"t":"%s","b":"0","a":"0","m":false,"R":true,"wt":"CONTRACT_PRICE",
                  "ot":"MARKET","ps":"BOTH","cp":false,"rp":"12.5","pP":false,"si":0,"ss":0,
                  "V":"EXPIRE_MAKER","pm":"NONE","gtd":0}}
                """.formatted(tradeId);
    }
}
