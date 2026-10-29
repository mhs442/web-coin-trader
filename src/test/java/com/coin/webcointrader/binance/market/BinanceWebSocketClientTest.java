package com.coin.webcointrader.binance.market;

import com.coin.webcointrader.binance.market.dto.BinanceWsAggTradeDTO;
import com.coin.webcointrader.binance.market.dto.BinanceWsKlineDTO;
import com.coin.webcointrader.binance.market.dto.BinanceWsTickerDTO;
import com.fasterxml.jackson.databind.JsonNode;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * Binance 공개 WebSocket(/market) 클라이언트 단위 테스트.
 * 구독 메시지 형식·묶음 전송(초당 메시지 수 제한 대응)과 수신 메시지 분기를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class BinanceWebSocketClientTest {

    private BinanceWebSocketClient client;

    @Mock
    private StandardWebSocketClient webSocketClient;

    @Mock
    private WebSocketSession session;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        client = new BinanceWebSocketClient(webSocketClient, objectMapper);
        // @Value 필드 직접 설정 (@PostConstruct는 호출하지 않음)
        ReflectionTestUtils.setField(client, "websocketUrl", "ws://localhost:0/mock-binance-ws");
        ReflectionTestUtils.setField(client, "reconnectDelay", 1000L);
        ReflectionTestUtils.setField(client, "reconnectMaxDelay", 30000L);
        ReflectionTestUtils.setField(client, "shouldConnect", false); // 자동 재연결 비활성화
        lenient().when(session.isOpen()).thenReturn(true);
    }

    // ─────────────────────────────────────────────
    // 구독 동기화
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("syncSubscriptions: 즉시 보내지 않고, flush 시 추가분을 소문자 ticker·kline_1m 스트림으로 한 메시지에 묶어 구독한다")
    void sync_batchesSubscribeOnFlush() throws Exception {
        client.afterConnectionEstablished(session);

        client.syncSubscriptions(Set.of("BTCUSDT"));
        client.syncSubscriptions(Set.of("BTCUSDT", "ETHUSDT"));
        then(session).should(never()).sendMessage(any());

        client.flushSubscriptions();

        List<JsonNode> sent = sentMessages(1);
        assertThat(sent.get(0).get("method").asText()).isEqualTo("SUBSCRIBE");
        assertThat(params(sent.get(0))).containsExactlyInAnyOrder(
                "btcusdt@ticker", "btcusdt@kline_1m", "ethusdt@ticker", "ethusdt@kline_1m");
        assertThat(client.getSubscribedSymbols()).containsExactlyInAnyOrder("BTCUSDT", "ETHUSDT");
    }

    @Test
    @DisplayName("flushSubscriptions: 제외된 심볼은 UNSUBSCRIBE 한 메시지로 해지한다")
    void flush_unsubscribesRemoved() throws Exception {
        client.afterConnectionEstablished(session);
        client.syncSubscriptions(Set.of("BTCUSDT", "ETHUSDT"));
        client.flushSubscriptions();

        client.syncSubscriptions(Set.of("BTCUSDT"));
        client.flushSubscriptions();

        List<JsonNode> sent = sentMessages(2);
        assertThat(sent.get(1).get("method").asText()).isEqualTo("UNSUBSCRIBE");
        assertThat(params(sent.get(1))).containsExactlyInAnyOrder("ethusdt@ticker", "ethusdt@kline_1m");
        assertThat(client.getSubscribedSymbols()).containsExactly("BTCUSDT");
    }

    @Test
    @DisplayName("flushSubscriptions: 바뀐 것이 없으면 메시지를 보내지 않는다")
    void flush_noChangeSendsNothing() throws Exception {
        client.afterConnectionEstablished(session);
        client.syncSubscriptions(Set.of("BTCUSDT"));
        client.flushSubscriptions();

        client.flushSubscriptions();

        then(session).should(times(1)).sendMessage(any());
    }

    @Test
    @DisplayName("flushSubscriptions: 연결 전이면 보내지 않고 구독 상태도 바꾸지 않는다 (연결 후 다시 시도)")
    void flush_notConnectedKeepsPending() throws Exception {
        client.syncSubscriptions(Set.of("BTCUSDT"));

        client.flushSubscriptions();

        assertThat(client.getSubscribedSymbols()).isEmpty();
        client.afterConnectionEstablished(session);
        client.flushSubscriptions();
        assertThat(client.getSubscribedSymbols()).containsExactly("BTCUSDT");
    }

    @Test
    @DisplayName("재연결: 연결이 끊기면 구독 상태를 비워, 다음 flush에서 목표 심볼 전체를 다시 구독한다")
    void reconnect_resubscribesAll() throws Exception {
        client.afterConnectionEstablished(session);
        client.syncSubscriptions(Set.of("BTCUSDT"));
        client.flushSubscriptions();

        client.afterConnectionClosed(session, org.springframework.web.socket.CloseStatus.NORMAL);
        assertThat(client.getSubscribedSymbols()).isEmpty();

        client.afterConnectionEstablished(session);
        client.flushSubscriptions();

        List<JsonNode> sent = sentMessages(2);
        assertThat(sent.get(1).get("method").asText()).isEqualTo("SUBSCRIBE");
        assertThat(params(sent.get(1))).containsExactlyInAnyOrder("btcusdt@ticker", "btcusdt@kline_1m");
    }

    @Test
    @DisplayName("syncTradeSubscriptions: 체결 대상 심볼의 @aggTrade 스트림을 ticker·kline과 같은 SUBSCRIBE 메시지로 묶는다")
    void syncTrade_batchesAggTradeInSameSubscribe() throws Exception {
        client.afterConnectionEstablished(session);
        client.syncSubscriptions(Set.of("BTCUSDT", "ETHUSDT"));
        client.syncTradeSubscriptions(Set.of("BTCUSDT"));

        client.flushSubscriptions();

        List<JsonNode> sent = sentMessages(1);
        assertThat(params(sent.get(0))).containsExactlyInAnyOrder(
                "btcusdt@ticker", "btcusdt@kline_1m", "ethusdt@ticker", "ethusdt@kline_1m", "btcusdt@aggTrade");
        assertThat(client.getSubscribedTradeSymbols()).containsExactly("BTCUSDT");
    }

    @Test
    @DisplayName("syncTradeSubscriptions: 체결 대상에서 빠진 심볼은 @aggTrade만 해지하고 ticker·kline은 유지한다")
    void syncTrade_unsubscribesOnlyAggTrade() throws Exception {
        client.afterConnectionEstablished(session);
        client.syncSubscriptions(Set.of("BTCUSDT"));
        client.syncTradeSubscriptions(Set.of("BTCUSDT"));
        client.flushSubscriptions();

        client.syncTradeSubscriptions(Set.of());
        client.flushSubscriptions();

        List<JsonNode> sent = sentMessages(2);
        assertThat(sent.get(1).get("method").asText()).isEqualTo("UNSUBSCRIBE");
        assertThat(params(sent.get(1))).containsExactly("btcusdt@aggTrade");
        assertThat(client.getSubscribedSymbols()).containsExactly("BTCUSDT");
        assertThat(client.getSubscribedTradeSymbols()).isEmpty();
    }

    @Test
    @DisplayName("재연결: 연결이 끊기면 체결 구독 상태도 비워 다시 구독한다")
    void reconnect_clearsTradeSubscriptions() throws Exception {
        client.afterConnectionEstablished(session);
        client.syncTradeSubscriptions(Set.of("BTCUSDT"));
        client.flushSubscriptions();

        client.afterConnectionClosed(session, org.springframework.web.socket.CloseStatus.NORMAL);

        assertThat(client.getSubscribedTradeSymbols()).isEmpty();
    }

    // ─────────────────────────────────────────────
    // 수신 메시지 분기
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("handleTextMessage: 24hrTicker 메시지를 티커 콜백으로 전달한다")
    void handle_ticker() throws Exception {
        AtomicReference<BinanceWsTickerDTO> received = new AtomicReference<>();
        client.addTickerCallback(received::set);

        client.handleTextMessage(session, new TextMessage("""
                {"e":"24hrTicker","E":1761526800000,"s":"BTCUSDT","p":"800.0","P":"1.230",
                 "c":"67000.1","h":"68000","l":"66000","v":"1000","q":"67000000"}
                """));

        assertThat(received.get().getSymbol()).isEqualTo("BTCUSDT");
        assertThat(received.get().getLastPrice()).isEqualTo("67000.1");
        assertThat(received.get().getPriceChangePercent()).isEqualTo("1.230");
        assertThat(received.get().getQuoteVolume()).isEqualTo("67000000");
    }

    @Test
    @DisplayName("handleTextMessage: kline 메시지를 캔들 콜백으로 전달한다")
    void handle_kline() throws Exception {
        AtomicReference<BinanceWsKlineDTO> received = new AtomicReference<>();
        client.addKlineCallback(received::set);

        client.handleTextMessage(session, new TextMessage("""
                {"e":"kline","E":1761526800000,"s":"BTCUSDT",
                 "k":{"t":1761526740000,"T":1761526799999,"s":"BTCUSDT","i":"1m",
                      "o":"67010.2","c":"67090.9","h":"67100.0","l":"67000.0","v":"8.001","x":true}}
                """));

        assertThat(received.get().getSymbol()).isEqualTo("BTCUSDT");
        assertThat(received.get().getKline().getStartTime()).isEqualTo(1761526740000L);
        assertThat(received.get().getKline().getCloseTime()).isEqualTo(1761526799999L);
        assertThat(received.get().getKline().getClose()).isEqualTo("67090.9");
        assertThat(received.get().getKline().isClosed()).isTrue();
    }

    @Test
    @DisplayName("handleTextMessage: aggTrade 메시지를 체결 콜백으로 전달한다")
    void handle_aggTrade() throws Exception {
        AtomicReference<BinanceWsAggTradeDTO> received = new AtomicReference<>();
        client.addTradeCallback(received::set);

        client.handleTextMessage(session, new TextMessage("""
                {"e":"aggTrade","E":1761526800010,"s":"BTCUSDT","a":5933014,"p":"67000.1","q":"0.125",
                 "f":100,"l":105,"T":1761526800005,"m":true}
                """));

        assertThat(received.get().getSymbol()).isEqualTo("BTCUSDT");
        assertThat(received.get().getQty()).isEqualTo("0.125");
        assertThat(received.get().getTradeTime()).isEqualTo(1761526800005L);
    }

    @Test
    @DisplayName("handleTextMessage: 구독 응답({\"result\":null,\"id\":1})과 깨진 메시지는 무시한다")
    void handle_ignoresControlAndBroken() throws Exception {
        List<Object> received = new ArrayList<>();
        client.addTickerCallback(received::add);
        client.addKlineCallback(received::add);

        client.handleTextMessage(session, new TextMessage("{\"result\":null,\"id\":1}"));
        client.handleTextMessage(session, new TextMessage("not-json"));

        assertThat(received).isEmpty();
    }

    // ─────────────────────────────────────────────
    // 헬퍼 메서드
    // ─────────────────────────────────────────────

    private List<JsonNode> sentMessages(int expected) throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        then(session).should(times(expected)).sendMessage(captor.capture());
        List<JsonNode> nodes = new ArrayList<>();
        for (TextMessage m : captor.getAllValues()) {
            nodes.add(objectMapper.readTree(m.getPayload()));
        }
        return nodes;
    }

    private List<String> params(JsonNode message) {
        List<String> params = new ArrayList<>();
        message.get("params").forEach(p -> params.add(p.asText()));
        return params;
    }
}
