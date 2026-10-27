package com.coin.webcointrader.bybit.market;

import com.coin.webcointrader.bybit.market.dto.WebSocketKlineDTO;
import com.coin.webcointrader.bybit.market.dto.WebSocketTickerDTO;
import com.coin.webcointrader.bybit.market.dto.FindTickerResponse;
import com.coin.webcointrader.bybit.market.dto.GetInstrumentsInfoResponse;
import com.coin.webcointrader.bybit.market.dto.GetKlineResponse;
import com.coin.webcointrader.bybit.market.dto.OrderBookResponse;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.market.dto.InstrumentDTO;
import com.coin.webcointrader.common.market.dto.KlineDTO;
import com.coin.webcointrader.common.market.dto.OrderBookDTO;
import com.coin.webcointrader.common.market.dto.TickerDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.io.File;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

/**
 * Bybit 시세 클라이언트 단위 테스트.
 * Bybit 응답(REST·WebSocket)을 공통 DTO로 변환하는 부분을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class BybitMarketClientTest {

    private static final String FILES = "src/test/resources/__files/";

    @Mock
    private MarketClient marketClient;

    @Mock
    private BybitWebSocketClient bybitWebSocketClient;

    private BybitMarketClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        client = new BybitMarketClient(marketClient, bybitWebSocketClient, Clock.systemDefaultZone());
    }

    @Test
    @DisplayName("getExchangeType: BYBIT")
    void exchangeType() {
        assertThat(client.getExchangeType()).isEqualTo(ExchangeType.BYBIT);
    }

    // ─────────────────────────────────────────────
    // 티커 목록 (REST)
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("refreshTickers: USDT로 끝나지 않는 심볼은 제외한다")
    void refreshTickers_filtersNonUsdt() {
        givenTickers(List.of(
                makeTicker("BTCUSDT", "1000.0"),
                makeTicker("ETHUSDT", "500.0"),
                makeTicker("BTCETH", "2.0")));  // USDT 아님 → 제외

        client.refreshTickers();

        assertThat(client.getTickers()).extracting(TickerDTO::getSymbol)
                .containsExactlyInAnyOrder("BTCUSDT", "ETHUSDT");
    }

    @Test
    @DisplayName("refreshTickers: 프리런치 진행 중(curPreListingPhase가 Finished 외 값)인 종목은 제외한다")
    void refreshTickers_filtersPreListing() {
        FindTickerResponse.TickerInfo preListing = makeTicker("OPENAIUSDT", "1470.0");
        preListing.setCurPreListingPhase("ContinuousTrading"); // 프리런치 진행 중 → 제외
        givenTickers(List.of(makeTicker("BTCUSDT", "1000.0"), preListing));

        client.refreshTickers();

        assertThat(client.getTickers()).extracting(TickerDTO::getSymbol).containsExactly("BTCUSDT");
    }

    @Test
    @DisplayName("refreshTickers: 프리런치가 끝났거나(Finished) 원래 정식 종목(빈 값)이면 유지한다")
    void refreshTickers_keepsFinishedAndRegular() {
        FindTickerResponse.TickerInfo finished = makeTicker("ETHUSDT", "500.0");
        finished.setCurPreListingPhase("Finished"); // 정식 전환 완료
        FindTickerResponse.TickerInfo regular = makeTicker("BTCUSDT", "1000.0");
        regular.setCurPreListingPhase("");          // 원래부터 정식 종목
        givenTickers(List.of(finished, regular));

        client.refreshTickers();

        assertThat(client.getTickers()).extracting(TickerDTO::getSymbol)
                .containsExactlyInAnyOrder("ETHUSDT", "BTCUSDT");
    }

    @Test
    @DisplayName("refreshTickers: 거래대금(turnover24h) 내림차순으로 정렬하고 필드를 그대로 옮긴다")
    void refreshTickers_sortsByTurnoverAndMapsFields() {
        FindTickerResponse.TickerInfo eth = makeTicker("ETHUSDT", "500.0");
        eth.setTurnover24h("500000.0");   // 3등
        FindTickerResponse.TickerInfo btc = makeTicker("BTCUSDT", "10000.0");
        btc.setTurnover24h("10000000.0"); // 1등
        FindTickerResponse.TickerInfo sol = makeTicker("SOLUSDT", "2000.0");
        sol.setTurnover24h("2000000.0");  // 2등
        givenTickers(List.of(eth, btc, sol));

        client.refreshTickers();

        List<TickerDTO> tickers = client.getTickers();
        assertThat(tickers).extracting(TickerDTO::getSymbol).containsExactly("BTCUSDT", "SOLUSDT", "ETHUSDT");
        TickerDTO first = tickers.get(0);
        assertThat(first.getLastPrice()).isEqualTo("1000.0");
        assertThat(first.getPrice24hPcnt()).isEqualTo("0.01");
        assertThat(first.getHighPrice24h()).isEqualTo("1100.0");
        assertThat(first.getLowPrice24h()).isEqualTo("900.0");
        assertThat(first.getVolume24h()).isEqualTo("10000.0");
        assertThat(first.getTurnover24h()).isEqualTo("10000000.0");
    }

    @Test
    @DisplayName("refreshTickers: 응답이 비어 있으면 기존 캐시를 유지한다")
    void refreshTickers_nullResponseKeepsCache() {
        givenTickers(List.of(makeTicker("BTCUSDT", "1000.0")));
        client.refreshTickers();
        given(marketClient.getTickers("linear")).willReturn(ResponseEntity.ok(null));

        client.refreshTickers();

        assertThat(client.getTickers()).extracting(TickerDTO::getSymbol).containsExactly("BTCUSDT");
    }

    // ─────────────────────────────────────────────
    // 거래 규칙 (REST, 페이징)
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("refreshInstruments: nextPageCursor를 따라 모든 페이지의 수량·가격 단위를 캐시한다")
    void refreshInstruments_paginates() {
        given(marketClient.getInstrumentsInfo("linear", 1000, null)).willReturn(ResponseEntity.ok(
                makeInstrumentsPage(List.of(makeInstrument("BTCUSDT", "0.001", "0.10")), "cursor2")));
        given(marketClient.getInstrumentsInfo("linear", 1000, "cursor2")).willReturn(ResponseEntity.ok(
                makeInstrumentsPage(List.of(makeInstrument("DOGEUSDT", "1", "0.00001")), "")));

        client.refreshInstruments();

        InstrumentDTO btc = client.getInstrument("BTCUSDT");
        assertThat(btc.getQtyStep()).isEqualTo("0.001");
        assertThat(btc.getTickSize()).isEqualTo("0.10");
        assertThat(btc.getMinOrderQty()).isEqualTo("0.001");
        assertThat(client.getInstrument("DOGEUSDT").getTickSize()).isEqualTo("0.00001");
        // 캐시 적중 → 추가 호출 없음
        then(marketClient).should(times(1)).getInstrumentsInfo("linear", 1000, null);
        then(marketClient).should(times(1)).getInstrumentsInfo("linear", 1000, "cursor2");
    }

    @Test
    @DisplayName("refreshInstruments: 응답이 null이면 캐시가 비어 있고 조회 결과는 null")
    void refreshInstruments_nullResponse() {
        given(marketClient.getInstrumentsInfo("linear", 1000, null)).willReturn(ResponseEntity.ok(null));

        client.refreshInstruments();

        assertThat(client.getInstrument("BTCUSDT")).isNull();
    }

    // ─────────────────────────────────────────────
    // 캔들·호가창 (REST)
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("getKlines: Bybit 최신순 배열을 시작 시각 오름차순 캔들로 변환한다")
    void getKlines_convertsAscending() throws Exception {
        GetKlineResponse response = objectMapper.readValue(new File(FILES + "MarketClient_getKline.json"), GetKlineResponse.class);
        given(marketClient.getKline("linear", "BTCUSDT", "60", 200)).willReturn(ResponseEntity.ok(response));

        List<KlineDTO> klines = client.getKlines("BTCUSDT", "60", 200);

        assertThat(klines).extracting(KlineDTO::getStartTime).containsExactly(1699999740000L, 1699999800000L);
        KlineDTO latest = klines.get(1);
        assertThat(latest.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(latest.getOpen()).isEqualTo("50000");
        assertThat(latest.getHigh()).isEqualTo("51000");
        assertThat(latest.getLow()).isEqualTo("49000");
        assertThat(latest.getClose()).isEqualTo("50500");
        assertThat(latest.getVolume()).isEqualTo("100.5");
    }

    @Test
    @DisplayName("getKlines: retCode가 0이 아니면 시세 조회 실패 예외를 던진다")
    void getKlines_errorThrows() throws Exception {
        GetKlineResponse response = objectMapper.readValue(
                "{\"retCode\":\"10001\",\"retMsg\":\"params error\"}", GetKlineResponse.class);
        given(marketClient.getKline("linear", "BTCUSDT", "60", 200)).willReturn(ResponseEntity.ok(response));

        assertThatThrownBy(() -> client.getKlines("BTCUSDT", "60", 200)).isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("getOrderBook: a/b 배열을 asks/bids 호가 단계로 변환한다")
    void getOrderBook_converts() throws Exception {
        OrderBookResponse response = objectMapper.readValue(new File(FILES + "MarketClient_getOrderBook.json"), OrderBookResponse.class);
        given(marketClient.getOrderBook("linear", "BTCUSDT", 50)).willReturn(ResponseEntity.ok(response));

        OrderBookDTO book = client.getOrderBook("BTCUSDT", 50);

        assertThat(book.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(book.getTimestamp()).isEqualTo(1699999800000L);
        assertThat(book.getAsks()).extracting(OrderBookDTO.Level::getPrice).containsExactly("50001.00", "50002.00");
        assertThat(book.getAsks().get(0).getQty()).isEqualTo("1.2");
        assertThat(book.getBids()).extracting(OrderBookDTO.Level::getPrice).containsExactly("49999.00", "49998.00");
        assertThat(book.getBids().get(0).getQty()).isEqualTo("1.5");
    }

    @Test
    @DisplayName("getOrderBook: 응답이 없으면 시세 조회 실패 예외를 던진다")
    void getOrderBook_nullThrows() {
        given(marketClient.getOrderBook("linear", "BTCUSDT", 50)).willReturn(ResponseEntity.ok(null));

        assertThatThrownBy(() -> client.getOrderBook("BTCUSDT", 50)).isInstanceOf(CustomException.class);
    }

    // ─────────────────────────────────────────────
    // 실시간 스트림 (WebSocket)
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("init: WebSocket 클라이언트에 ticker/kline 콜백을 등록한다")
    void init_registersCallbacks() {
        client.init();

        then(bybitWebSocketClient).should().addTickerCallback(any());
        then(bybitWebSocketClient).should().addKlineCallback(any());
    }

    @Test
    @DisplayName("ticker snapshot: 전체 데이터를 저장하고 리스너에 전달한다")
    void tickerSnapshot_storesAndPublishes() {
        Consumer<WebSocketTickerDTO> tickerCallback = captureTickerCallback();
        List<TickerDTO> received = new ArrayList<>();
        client.addTickerListener(received::add);

        tickerCallback.accept(makeWsTicker("snapshot", "BTCUSDT", "50000.00", "10000.0"));

        TickerDTO stored = client.getTicker("BTCUSDT");
        assertThat(stored.getLastPrice()).isEqualTo("50000.00");
        assertThat(stored.getVolume24h()).isEqualTo("10000.0");
        assertThat(received).extracting(TickerDTO::getLastPrice).containsExactly("50000.00");
        assertThat(client.isWsActive()).isTrue();
    }

    @Test
    @DisplayName("ticker delta: 기존 데이터에 변경된 필드만 병합한다")
    void tickerDelta_mergesChangedFieldsOnly() {
        Consumer<WebSocketTickerDTO> tickerCallback = captureTickerCallback();
        tickerCallback.accept(makeWsTicker("snapshot", "BTCUSDT", "50000.00", "10000.0"));

        tickerCallback.accept(makeWsTicker("delta", "BTCUSDT", "50100.00", null)); // 가격만 변경

        TickerDTO stored = client.getTicker("BTCUSDT");
        assertThat(stored.getLastPrice()).isEqualTo("50100.00");  // 변경됨
        assertThat(stored.getVolume24h()).isEqualTo("10000.0");    // 유지됨
    }

    @Test
    @DisplayName("ticker delta: 기존 데이터가 없으면 새로 만든다")
    void tickerDelta_createsWhenNoExisting() {
        Consumer<WebSocketTickerDTO> tickerCallback = captureTickerCallback();

        tickerCallback.accept(makeWsTicker("delta", "ETHUSDT", "3000.00", null));

        assertThat(client.getTicker("ETHUSDT").getLastPrice()).isEqualTo("3000.00");
    }

    @Test
    @DisplayName("ticker: null DTO·data·symbol은 무시한다")
    void ticker_ignoresInvalid() {
        Consumer<WebSocketTickerDTO> tickerCallback = captureTickerCallback();

        tickerCallback.accept(null);
        tickerCallback.accept(new WebSocketTickerDTO());
        tickerCallback.accept(makeWsTicker("snapshot", null, "1", null));

        assertThat(client.isWsActive()).isFalse();
    }

    @Test
    @DisplayName("kline: 마감된 봉만 토픽에서 추출한 심볼과 함께 리스너에 전달한다")
    void kline_confirmedOnlyWithSymbolFromTopic() {
        client.init();
        ArgumentCaptor<Consumer<WebSocketKlineDTO>> captor = ArgumentCaptor.forClass(Consumer.class);
        then(bybitWebSocketClient).should().addKlineCallback(captor.capture());
        List<KlineDTO> received = new ArrayList<>();
        client.addKlineListener(received::add);

        WebSocketKlineDTO dto = new WebSocketKlineDTO();
        dto.setTopic("kline.1.BTCUSDT");
        dto.setData(List.of(makeWsKline(1_000L, false), makeWsKline(60_000L, true)));
        captor.getValue().accept(dto);

        assertThat(received).hasSize(1);
        KlineDTO kline = received.get(0);
        assertThat(kline.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(kline.getStartTime()).isEqualTo(60_000L);
        assertThat(kline.getEndTime()).isEqualTo(119_999L);
        assertThat(kline.getOpen()).isEqualTo("100");
        assertThat(kline.getClose()).isEqualTo("110");
        assertThat(kline.isConfirmed()).isTrue();
    }

    @Test
    @DisplayName("syncSubscriptions: WebSocket 클라이언트 구독 동기화에 위임한다")
    void syncSubscriptions_delegates() {
        client.syncSubscriptions(Set.of("BTCUSDT"));

        then(bybitWebSocketClient).should().syncSubscriptions(Set.of("BTCUSDT"));
    }

    // ─────────────────────────────────────────────
    // 헬퍼
    // ─────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Consumer<WebSocketTickerDTO> captureTickerCallback() {
        client.init();
        ArgumentCaptor<Consumer<WebSocketTickerDTO>> captor = ArgumentCaptor.forClass(Consumer.class);
        then(bybitWebSocketClient).should().addTickerCallback(captor.capture());
        return captor.getValue();
    }

    private void givenTickers(List<FindTickerResponse.TickerInfo> tickers) {
        FindTickerResponse response = new FindTickerResponse();
        FindTickerResponse.Result result = new FindTickerResponse.Result();
        result.setCategory("linear");
        result.setList(new ArrayList<>(tickers));
        response.setResult(result);
        response.setRetCode("0");
        given(marketClient.getTickers("linear")).willReturn(ResponseEntity.ok(response));
    }

    private FindTickerResponse.TickerInfo makeTicker(String symbol, String volume) {
        FindTickerResponse.TickerInfo ticker = new FindTickerResponse.TickerInfo();
        ticker.setSymbol(symbol);
        ticker.setVolume24h(volume);
        ticker.setLastPrice("1000.0");
        ticker.setPrice24hPcnt("0.01");
        ticker.setHighPrice24h("1100.0");
        ticker.setLowPrice24h("900.0");
        ticker.setTurnover24h("1000000.0");
        return ticker;
    }

    private GetInstrumentsInfoResponse makeInstrumentsPage(List<GetInstrumentsInfoResponse.InstrumentInfo> list,
                                                           String nextPageCursor) {
        GetInstrumentsInfoResponse response = new GetInstrumentsInfoResponse();
        GetInstrumentsInfoResponse.Result result = new GetInstrumentsInfoResponse.Result();
        result.setCategory("linear");
        result.setList(list);
        result.setNextPageCursor(nextPageCursor);
        response.setResult(result);
        return response;
    }

    private GetInstrumentsInfoResponse.InstrumentInfo makeInstrument(String symbol, String qtyStep, String tickSize) {
        GetInstrumentsInfoResponse.InstrumentInfo info = new GetInstrumentsInfoResponse.InstrumentInfo();
        info.setSymbol(symbol);
        GetInstrumentsInfoResponse.LotSizeFilter lot = new GetInstrumentsInfoResponse.LotSizeFilter();
        lot.setQtyStep(qtyStep);
        lot.setMinOrderQty(qtyStep);
        info.setLotSizeFilter(lot);
        GetInstrumentsInfoResponse.PriceFilter price = new GetInstrumentsInfoResponse.PriceFilter();
        price.setTickSize(tickSize);
        info.setPriceFilter(price);
        return info;
    }

    private WebSocketTickerDTO makeWsTicker(String type, String symbol, String lastPrice, String volume24h) {
        WebSocketTickerDTO dto = new WebSocketTickerDTO();
        dto.setTopic("tickers." + symbol);
        dto.setType(type);
        WebSocketTickerDTO.TickerData data = new WebSocketTickerDTO.TickerData();
        data.setSymbol(symbol);
        data.setLastPrice(lastPrice);
        data.setVolume24h(volume24h);
        dto.setData(data);
        return dto;
    }

    private WebSocketKlineDTO.KlineData makeWsKline(long startMs, boolean confirm) {
        WebSocketKlineDTO.KlineData k = new WebSocketKlineDTO.KlineData();
        k.setStart(startMs);
        k.setEnd(startMs + 59_999);
        k.setOpen("100");
        k.setHigh("120");
        k.setLow("90");
        k.setClose("110");
        k.setVolume("5");
        k.setConfirm(confirm);
        return k;
    }
}
