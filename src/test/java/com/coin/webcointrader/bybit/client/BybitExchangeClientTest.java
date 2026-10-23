package com.coin.webcointrader.bybit.client;

import com.coin.webcointrader.bybit.client.account.AccountClient;
import com.coin.webcointrader.bybit.client.market.BybitWebSocketClient;
import com.coin.webcointrader.bybit.client.market.dto.WebSocketKlineDTO;
import com.coin.webcointrader.bybit.client.market.dto.WebSocketTickerDTO;
import com.coin.webcointrader.bybit.client.privatews.BybitPrivateWebSocketClient;
import com.coin.webcointrader.bybit.client.privatews.dto.WebSocketExecutionDTO;
import com.coin.webcointrader.common.enums.PositionCloseType;
import com.coin.webcointrader.common.exchange.PositionCloseDTO;
import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.util.AesEncryptor;
import com.coin.webcointrader.login.dto.ExchangeKeyDTO;
import com.coin.webcointrader.login.service.BybitApiKeyValidator;
import com.coin.webcointrader.login.service.ExchangeKeyService;
import com.coin.webcointrader.market.service.MarketService;
import com.coin.webcointrader.trade.service.TradeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class BybitExchangeClientTest {

    @InjectMocks
    private BybitExchangeClient bybitExchangeClient;

    @Mock
    private BybitWebSocketClient bybitWebSocketClient;

    @Mock
    private MarketService marketService;

    @Mock
    private TradeService tradeService;

    @Mock
    private AccountClient accountClient;

    @Mock
    private ExchangeKeyService exchangeKeyService;

    @Mock
    private AesEncryptor aesEncryptor;

    @Mock
    private BybitApiKeyValidator bybitApiKeyValidator;

    @Mock
    private BybitPrivateWebSocketClient bybitPrivateWebSocketClient;

    @Captor
    private ArgumentCaptor<Consumer<WebSocketTickerDTO>> tickerCaptor;

    @Captor
    private ArgumentCaptor<Consumer<WebSocketKlineDTO>> klineCaptor;

    @Test
    @DisplayName("init: WebSocket 클라이언트에 ticker/kline 콜백을 등록한다")
    void init_registersCallbacks() {
        // when
        bybitExchangeClient.init();

        // then
        then(bybitWebSocketClient).should().addTickerCallback(any());
        then(bybitWebSocketClient).should().addKlineCallback(any());
    }

    @Test
    @DisplayName("ticker 수신 시 등록된 가격 콜백에 (심볼, 현재가)를 전달한다")
    void ticker_fanout() {
        // given
        List<String> received = new ArrayList<>();
        bybitExchangeClient.registerPriceCallback((symbol, price) -> received.add(symbol + ":" + price));
        bybitExchangeClient.init();
        then(bybitWebSocketClient).should().addTickerCallback(tickerCaptor.capture());

        // when
        tickerCaptor.getValue().accept(makeTicker("BTCUSDT", "65000.5"));

        // then
        assertThat(received).containsExactly("BTCUSDT:65000.5");
    }

    @Test
    @DisplayName("lastPrice 없는 ticker delta는 가격 콜백을 호출하지 않는다")
    void ticker_deltaWithoutPrice_skip() {
        // given
        List<String> received = new ArrayList<>();
        bybitExchangeClient.registerPriceCallback((symbol, price) -> received.add(symbol));
        bybitExchangeClient.init();
        then(bybitWebSocketClient).should().addTickerCallback(tickerCaptor.capture());

        // when — delta 메시지에서 누락된 필드는 '변경 없음'을 의미
        tickerCaptor.getValue().accept(makeTicker("BTCUSDT", null));

        // then
        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("confirm=true 봉만 토픽에서 추출한 심볼과 함께 kline 콜백에 전달한다")
    void kline_confirmedOnly() {
        // given
        List<String> received = new ArrayList<>();
        bybitExchangeClient.registerKlineCallback((symbol, kline) -> received.add(symbol + ":" + kline.getClose()));
        bybitExchangeClient.init();
        then(bybitWebSocketClient).should().addKlineCallback(klineCaptor.capture());

        WebSocketKlineDTO dto = new WebSocketKlineDTO();
        dto.setTopic("kline.1.BTCUSDT");
        dto.setData(List.of(makeKline("100", false), makeKline("101", true)));

        // when
        klineCaptor.getValue().accept(dto);

        // then — 미마감 봉(confirm=false)은 제외
        assertThat(received).containsExactly("BTCUSDT:101");
    }

    // ─────────────────────────────────────────────
    // syncPrivateStreams
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("syncPrivateStreams: 연결되지 않은 대상 사용자는 키를 복호화해 연결을 연다")
    void syncPrivateStreams_opensNewUser() {
        // given
        given(bybitPrivateWebSocketClient.getConnectedUserIds()).willReturn(Set.of());
        givenKey(1L, "encKey", "encSecret", "apiKey", "apiSecret");

        // when
        bybitExchangeClient.syncPrivateStreams(Set.of(1L));

        // then
        then(bybitPrivateWebSocketClient).should().open(1L, "apiKey", "apiSecret");
    }

    @Test
    @DisplayName("syncPrivateStreams: 대상에서 빠진 사용자의 연결은 닫는다")
    void syncPrivateStreams_closesRemovedUser() {
        // given
        given(bybitPrivateWebSocketClient.getConnectedUserIds()).willReturn(Set.of(1L));

        // when
        bybitExchangeClient.syncPrivateStreams(Set.of());

        // then
        then(bybitPrivateWebSocketClient).should().close(1L);
        then(bybitPrivateWebSocketClient).should(never()).open(any(), any(), any());
    }

    @Test
    @DisplayName("syncPrivateStreams: 이미 연결된 사용자는 다시 열지 않는다 (키 조회도 하지 않음)")
    void syncPrivateStreams_skipsAlreadyConnected() {
        // given
        given(bybitPrivateWebSocketClient.getConnectedUserIds()).willReturn(Set.of(1L));

        // when
        bybitExchangeClient.syncPrivateStreams(Set.of(1L));

        // then
        then(exchangeKeyService).shouldHaveNoInteractions();
        then(bybitPrivateWebSocketClient).should(never()).open(any(), any(), any());
        then(bybitPrivateWebSocketClient).should(never()).close(any());
    }

    @Test
    @DisplayName("syncPrivateStreams: 키가 없는 사용자는 건너뛰고 다른 사용자는 정상 연결한다")
    void syncPrivateStreams_missingKey_skipsOnlyThatUser() {
        // given
        given(bybitPrivateWebSocketClient.getConnectedUserIds()).willReturn(Set.of());
        given(exchangeKeyService.findKey(1L, ExchangeType.BYBIT))
                .willThrow(new CustomException(ExceptionMessage.API_KEY_NOT_FOUND));
        givenKey(2L, "encKey2", "encSecret2", "apiKey2", "apiSecret2");

        // when
        bybitExchangeClient.syncPrivateStreams(new LinkedHashSet<>(List.of(1L, 2L)));

        // then
        then(bybitPrivateWebSocketClient).should().open(2L, "apiKey2", "apiSecret2");
        then(bybitPrivateWebSocketClient).should(never()).open(eq(1L), any(), any());
    }

    // ─────────────────────────────────────────────
    // 포지션 청산 체결 감지 (private WS execution)
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("init: private WS 클라이언트에 체결 콜백을 등록한다")
    void init_registersExecutionCallback() {
        bybitExchangeClient.init();

        then(bybitPrivateWebSocketClient).should().addExecutionCallback(any());
    }

    @Test
    @DisplayName("청산 체결(closedSize>0)의 마지막 건이면 포지션 청산 이벤트를 전달한다")
    void execution_closeFill_emitsEvent() {
        List<PositionCloseDTO> received = captureCloseEvents();

        execCallback().accept(7L, makeExec("Trade", "0.01", "0", "TakeProfit", "CreateByTakeProfit"));

        assertThat(received).hasSize(1);
        PositionCloseDTO event = received.get(0);
        assertThat(event.getUserId()).isEqualTo(7L);
        assertThat(event.getExchangeType()).isEqualTo(ExchangeType.BYBIT);
        assertThat(event.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(event.getOrderId()).isEqualTo("ext-1");
        assertThat(event.getOrderLinkId()).isEqualTo("");
        assertThat(event.getExecPrice()).isEqualTo("51000");
        assertThat(event.getCloseType()).isEqualTo(PositionCloseType.TAKE_PROFIT);
    }

    @Test
    @DisplayName("청산 종류: 스톱 주문 유형·체결 유형으로 익절/손절/강제청산/기타를 구분한다")
    void execution_closeTypeMapping() {
        List<PositionCloseDTO> received = captureCloseEvents();
        BiConsumer<Long, WebSocketExecutionDTO.ExecutionData> cb = execCallback();

        cb.accept(1L, makeExec("Trade", "0.01", "0", "StopLoss", "CreateByStopLoss"));
        cb.accept(1L, makeExec("BustTrade", "0.01", "0", "", "CreateByLiq"));
        cb.accept(1L, makeExec("Trade", "0.01", "0", "", "CreateByClosing"));
        cb.accept(1L, makeExec("AdlTrade", "0.01", "0", "", "CreateByAdl_PassThrough"));

        assertThat(received).extracting(PositionCloseDTO::getCloseType).containsExactly(
                PositionCloseType.STOP_LOSS, PositionCloseType.LIQUIDATION,
                PositionCloseType.OTHER, PositionCloseType.OTHER);
    }

    @Test
    @DisplayName("진입 체결·부분 체결 중간 건·펀딩비 정산은 청산 이벤트로 전달하지 않는다")
    void execution_nonCloseFills_ignored() {
        List<PositionCloseDTO> received = captureCloseEvents();
        BiConsumer<Long, WebSocketExecutionDTO.ExecutionData> cb = execCallback();

        cb.accept(1L, makeExec("Trade", "0", "0", "", "CreateByUser"));        // 진입 체결
        cb.accept(1L, makeExec("Trade", "0.005", "0.005", "", "CreateByUser")); // 부분 체결 (잔량 있음)
        cb.accept(1L, makeExec("Funding", "0", "0", "", ""));                  // 펀딩비

        assertThat(received).isEmpty();
    }

    /** 포지션 청산 리스너를 등록하고 수신 이벤트 목록을 반환 */
    private List<PositionCloseDTO> captureCloseEvents() {
        List<PositionCloseDTO> received = new ArrayList<>();
        bybitExchangeClient.addPositionCloseListener(received::add);
        return received;
    }

    /** init 시 private WS 클라이언트에 등록된 체결 콜백 */
    @SuppressWarnings("unchecked")
    private BiConsumer<Long, WebSocketExecutionDTO.ExecutionData> execCallback() {
        bybitExchangeClient.init();
        ArgumentCaptor<BiConsumer<Long, WebSocketExecutionDTO.ExecutionData>> captor =
                ArgumentCaptor.forClass(BiConsumer.class);
        then(bybitPrivateWebSocketClient).should().addExecutionCallback(captor.capture());
        return captor.getValue();
    }

    private WebSocketExecutionDTO.ExecutionData makeExec(String execType, String closedSize, String leavesQty,
                                                         String stopOrderType, String createType) {
        WebSocketExecutionDTO.ExecutionData d = new WebSocketExecutionDTO.ExecutionData();
        d.setSymbol("BTCUSDT");
        d.setOrderId("ext-1");
        d.setOrderLinkId("");
        d.setExecId("e-" + execType + "-" + createType);
        d.setExecType(execType);
        d.setExecPrice("51000");
        d.setExecQty("0.01");
        d.setClosedSize(closedSize);
        d.setLeavesQty(leavesQty);
        d.setStopOrderType(stopOrderType);
        d.setCreateType(createType);
        return d;
    }

    // ─────────────────────────────────────────────
    // 헬퍼 메서드
    // ─────────────────────────────────────────────

    private void givenKey(Long userId, String encKey, String encSecret, String apiKey, String apiSecret) {
        given(exchangeKeyService.findKey(userId, ExchangeType.BYBIT)).willReturn(ExchangeKeyDTO.builder()
                .userId(userId).exchangeType(ExchangeType.BYBIT).apiKey(encKey).apiSecret(encSecret).build());
        given(aesEncryptor.decrypt(encKey)).willReturn(apiKey);
        given(aesEncryptor.decrypt(encSecret)).willReturn(apiSecret);
    }

    private WebSocketTickerDTO makeTicker(String symbol, String lastPrice) {
        WebSocketTickerDTO.TickerData data = new WebSocketTickerDTO.TickerData();
        data.setSymbol(symbol);
        data.setLastPrice(lastPrice);
        WebSocketTickerDTO dto = new WebSocketTickerDTO();
        dto.setTopic("tickers." + symbol);
        dto.setData(data);
        return dto;
    }

    private WebSocketKlineDTO.KlineData makeKline(String close, boolean confirm) {
        WebSocketKlineDTO.KlineData kline = new WebSocketKlineDTO.KlineData();
        kline.setOpen("100");
        kline.setClose(close);
        kline.setConfirm(confirm);
        return kline;
    }
}
