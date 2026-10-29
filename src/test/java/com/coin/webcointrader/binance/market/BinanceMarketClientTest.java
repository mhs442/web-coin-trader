package com.coin.webcointrader.binance.market;

import com.coin.webcointrader.binance.common.BinanceApiException;
import com.coin.webcointrader.binance.market.dto.BinanceDepthResponse;
import com.coin.webcointrader.binance.market.dto.BinanceExchangeInfoResponse;
import com.coin.webcointrader.binance.market.dto.BinancePriceTickerResponse;
import com.coin.webcointrader.binance.market.dto.BinanceWsAggTradeDTO;
import com.coin.webcointrader.binance.market.dto.BinanceWsKlineDTO;
import com.coin.webcointrader.common.market.dto.PublicTradeDTO;
import com.coin.webcointrader.binance.market.dto.BinanceWsTickerDTO;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.market.dto.InstrumentDTO;
import com.coin.webcointrader.common.market.dto.KlineDTO;
import com.coin.webcointrader.common.market.dto.OrderBookDTO;
import com.coin.webcointrader.common.market.dto.TickerDTO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * Binance 시세 클라이언트 단위 테스트.
 * Binance 응답(REST·WebSocket)을 공통 DTO로 변환하는 부분과 차트용 1초 응답 캐시를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class BinanceMarketClientTest {

    private static final String FILES = "src/test/resources/__files/";

    @Mock
    private BinanceMarketFeignClient feignClient;

    @Mock
    private BinanceWebSocketClient webSocketClient;

    @Mock
    private Clock clock;

    private BinanceMarketClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        lenient().when(clock.millis()).thenReturn(1_000_000L);
        client = new BinanceMarketClient(feignClient, webSocketClient, clock);
    }

    @Test
    @DisplayName("getExchangeType: BINANCE")
    void exchangeType() {
        assertThat(client.getExchangeType()).isEqualTo(ExchangeType.BINANCE);
    }

    // ─────────────────────────────────────────────
    // 가격 목록 (REST, 자동매매 WS fallback용)
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("refreshTickers: ticker/price 응답을 심볼·현재가 티커로 캐시한다")
    void refreshTickers_mapsPrice() throws Exception {
        given(feignClient.getPriceTickers()).willReturn(objectMapper.readValue(
                new File(FILES + "BinanceMarketClient_getPriceTickers.json"),
                new TypeReference<List<BinancePriceTickerResponse>>() {}));

        client.refreshTickers();

        assertThat(client.getTickers())
                .extracting(TickerDTO::getSymbol, TickerDTO::getLastPrice)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("BTCUSDT", "67000.10"),
                        org.assertj.core.groups.Tuple.tuple("ETHUSDT", "3500.25"));
    }

    // ─────────────────────────────────────────────
    // 거래 규칙 (exchangeInfo)
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("refreshInstruments: USDT 무기한·거래 중(TRADING) 종목만 캐시한다 (분기물·USDC·정산 중 제외)")
    void refreshInstruments_filtersPerpetualUsdtTrading() throws Exception {
        givenExchangeInfo();

        client.refreshInstruments();

        assertThat(client.getInstrument("BTCUSDT")).isNotNull();
        assertThat(client.getInstrument("ETHUSDT")).isNotNull();
        assertThat(client.getInstrument("BTCUSDT_251226")).isNull();
        assertThat(client.getInstrument("BTCUSDC")).isNull();
        assertThat(client.getInstrument("OLDUSDT")).isNull();
    }

    @Test
    @DisplayName("refreshInstruments: 시장가 주문 기준이라 MARKET_LOT_SIZE를 우선하고, 없으면 LOT_SIZE를 쓴다")
    void refreshInstruments_prefersMarketLotSize() throws Exception {
        givenExchangeInfo();

        client.refreshInstruments();

        InstrumentDTO btc = client.getInstrument("BTCUSDT");
        assertThat(btc.getQtyStep()).isEqualTo("0.002");
        assertThat(btc.getMinOrderQty()).isEqualTo("0.002");
        assertThat(btc.getTickSize()).isEqualTo("0.10");

        InstrumentDTO eth = client.getInstrument("ETHUSDT");
        assertThat(eth.getQtyStep()).isEqualTo("0.001");
        assertThat(eth.getMinOrderQty()).isEqualTo("0.001");
        assertThat(eth.getTickSize()).isEqualTo("0.01");
    }

    // ─────────────────────────────────────────────
    // 캔들 (REST)
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("getKlines: 분 단위 간격을 Binance 형식으로 바꿔 조회하고, 오름차순 배열을 그대로 캔들로 변환한다")
    void getKlines_mapsIntervalAndRows() throws Exception {
        given(feignClient.getKlines("BTCUSDT", "1h", 200)).willReturn(klineRows());

        List<KlineDTO> klines = client.getKlines("BTCUSDT", "60", 200);

        assertThat(klines).hasSize(2);
        KlineDTO first = klines.get(0);
        assertThat(first.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(first.getStartTime()).isEqualTo(1761526680000L);
        assertThat(first.getEndTime()).isEqualTo(1761526739999L);
        assertThat(first.getOpen()).isEqualTo("67000.0");
        assertThat(first.getHigh()).isEqualTo("67050.5");
        assertThat(first.getLow()).isEqualTo("66990.0");
        assertThat(first.getClose()).isEqualTo("67010.2");
        assertThat(first.getVolume()).isEqualTo("12.345");
        assertThat(klines.get(1).getStartTime()).isEqualTo(1761526740000L);
    }

    @Test
    @DisplayName("getKlines: 일봉(D)·1분(1)도 Binance 간격(1d·1m)으로 변환한다")
    void getKlines_dailyAndMinute() throws Exception {
        given(feignClient.getKlines(anyString(), anyString(), anyInt())).willReturn(klineRows());

        client.getKlines("BTCUSDT", "D", 200);
        client.getKlines("BTCUSDT", "1", 200);

        then(feignClient).should().getKlines("BTCUSDT", "1d", 200);
        then(feignClient).should().getKlines("BTCUSDT", "1m", 200);
    }

    @Test
    @DisplayName("getKlines: 지원하지 않는 간격이면 호출 없이 시세 조회 실패 예외를 던진다")
    void getKlines_unsupportedInterval() {
        assertThatThrownBy(() -> client.getKlines("BTCUSDT", "7", 200))
                .isInstanceOf(CustomException.class);
        then(feignClient).should(never()).getKlines(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("getKlines: Binance 오류 응답이면 시세 조회 실패 예외로 감싼다")
    void getKlines_apiError() {
        given(feignClient.getKlines(anyString(), anyString(), anyInt()))
                .willThrow(new BinanceApiException(-1121, "Invalid symbol."));

        assertThatThrownBy(() -> client.getKlines("NOPEUSDT", "1", 200))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("-1121");
    }

    @Test
    @DisplayName("getKlines: 같은 요청은 1초 동안 캐시해 Binance를 다시 부르지 않고, 1초가 지나면 다시 조회한다")
    void getKlines_cachedForOneSecond() throws Exception {
        given(feignClient.getKlines("BTCUSDT", "1m", 200)).willReturn(klineRows());

        client.getKlines("BTCUSDT", "1", 200);
        given(clock.millis()).willReturn(1_000_999L);   // 999ms 경과 → 캐시
        client.getKlines("BTCUSDT", "1", 200);
        then(feignClient).should(times(1)).getKlines("BTCUSDT", "1m", 200);

        given(clock.millis()).willReturn(1_001_000L);   // 1초 경과 → 재조회
        client.getKlines("BTCUSDT", "1", 200);
        then(feignClient).should(times(2)).getKlines("BTCUSDT", "1m", 200);
    }

    // ─────────────────────────────────────────────
    // 호가창 (REST)
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("getOrderBook: bids/asks 배열을 호가 단계로 변환하고 T를 기준 시각으로 쓴다")
    void getOrderBook_mapsLevels() throws Exception {
        given(feignClient.getDepth("BTCUSDT", 50)).willReturn(depth());

        OrderBookDTO book = client.getOrderBook("BTCUSDT", 50);

        assertThat(book.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(book.getTimestamp()).isEqualTo(1761526800120L);
        assertThat(book.getAsks()).extracting(OrderBookDTO.Level::getPrice)
                .containsExactly("67000.1", "67000.2", "67000.3");
        assertThat(book.getBids()).extracting(OrderBookDTO.Level::getPrice)
                .containsExactly("67000.0", "66999.9", "66999.8");
        assertThat(book.getBids().get(0).getQty()).isEqualTo("1.500");
    }

    @Test
    @DisplayName("getOrderBook: 허용되지 않는 단계 수면 그보다 큰 허용값(5·10·20·50…)으로 조회 후 요청 단계 수만큼 자른다")
    void getOrderBook_roundsUpDepthAndTrims() throws Exception {
        given(feignClient.getDepth("BTCUSDT", 5)).willReturn(depth());

        OrderBookDTO book = client.getOrderBook("BTCUSDT", 2);

        assertThat(book.getAsks()).hasSize(2);
        assertThat(book.getBids()).hasSize(2);
    }

    @Test
    @DisplayName("getOrderBook: 같은 종목은 1초 동안 캐시한다")
    void getOrderBook_cachedForOneSecond() throws Exception {
        given(feignClient.getDepth("BTCUSDT", 50)).willReturn(depth());

        client.getOrderBook("BTCUSDT", 50);
        client.getOrderBook("BTCUSDT", 50);

        then(feignClient).should(times(1)).getDepth("BTCUSDT", 50);
    }

    // ─────────────────────────────────────────────
    // WebSocket
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("init: WebSocket 클라이언트에 ticker/kline 콜백을 등록한다")
    void init_registersCallbacks() {
        client.init();

        then(webSocketClient).should().addTickerCallback(any());
        then(webSocketClient).should().addKlineCallback(any());
    }

    @Test
    @DisplayName("ticker: 전체 값을 교체하고 퍼센트 변동률을 공통 형식(비율)으로 바꿔 리스너에 전달한다")
    void ticker_convertsPercentToRatio() {
        Consumer<BinanceWsTickerDTO> tickerCallback = captureTickerCallback();
        List<TickerDTO> received = new ArrayList<>();
        client.addTickerListener(received::add);

        BinanceWsTickerDTO dto = new BinanceWsTickerDTO();
        dto.setSymbol("BTCUSDT");
        dto.setLastPrice("67000.1");
        dto.setPriceChangePercent("1.230");
        dto.setHighPrice("68000");
        dto.setLowPrice("66000");
        dto.setVolume("1000");
        dto.setQuoteVolume("67000000");
        tickerCallback.accept(dto);

        TickerDTO ticker = client.getTicker("BTCUSDT");
        assertThat(ticker.getLastPrice()).isEqualTo("67000.1");
        assertThat(ticker.getPrice24hPcnt()).isEqualTo("0.0123");
        assertThat(ticker.getHighPrice24h()).isEqualTo("68000");
        assertThat(ticker.getLowPrice24h()).isEqualTo("66000");
        assertThat(ticker.getVolume24h()).isEqualTo("1000");
        assertThat(ticker.getTurnover24h()).isEqualTo("67000000");
        assertThat(received).hasSize(1);
    }

    @Test
    @DisplayName("ticker: null DTO·심볼은 무시한다")
    void ticker_ignoresNull() {
        Consumer<BinanceWsTickerDTO> tickerCallback = captureTickerCallback();

        tickerCallback.accept(null);
        tickerCallback.accept(new BinanceWsTickerDTO());

        assertThat(client.isWsActive()).isFalse();
    }

    @Test
    @DisplayName("kline: 마감된 봉(x=true)만 리스너에 전달한다")
    void kline_onlyClosed() {
        Consumer<BinanceWsKlineDTO> klineCallback = captureKlineCallback();
        List<KlineDTO> received = new ArrayList<>();
        client.addKlineListener(received::add);

        klineCallback.accept(makeWsKline(false));
        klineCallback.accept(makeWsKline(true));

        assertThat(received).hasSize(1);
        KlineDTO kline = received.get(0);
        assertThat(kline.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(kline.getStartTime()).isEqualTo(1761526740000L);
        assertThat(kline.getEndTime()).isEqualTo(1761526799999L);
        assertThat(kline.getClose()).isEqualTo("67090.9");
        assertThat(kline.isConfirmed()).isTrue();
    }

    @Test
    @DisplayName("aggTrade: 체결을 공통 체결 DTO로 바꿔 리스너에 전달한다")
    @SuppressWarnings("unchecked")
    void trade_publishesPublicTrade() {
        client.init();
        ArgumentCaptor<Consumer<BinanceWsAggTradeDTO>> captor = ArgumentCaptor.forClass(Consumer.class);
        then(webSocketClient).should().addTradeCallback(captor.capture());
        List<PublicTradeDTO> received = new ArrayList<>();
        client.addTradeListener(received::add);

        BinanceWsAggTradeDTO dto = new BinanceWsAggTradeDTO();
        dto.setSymbol("BTCUSDT");
        dto.setQty("0.125");
        dto.setTradeTime(1761526800005L);
        captor.getValue().accept(dto);
        captor.getValue().accept(null);

        assertThat(received).hasSize(1);
        assertThat(received.get(0).getSymbol()).isEqualTo("BTCUSDT");
        assertThat(received.get(0).getQty()).isEqualTo("0.125");
        assertThat(received.get(0).getTradeTime()).isEqualTo(1761526800005L);
    }

    @Test
    @DisplayName("syncTradeSubscriptions: WebSocket 클라이언트 체결 구독 동기화에 위임한다")
    void syncTradeSubscriptions_delegates() {
        client.syncTradeSubscriptions(Set.of("BTCUSDT"));

        then(webSocketClient).should().syncTradeSubscriptions(Set.of("BTCUSDT"));
    }

    @Test
    @DisplayName("syncSubscriptions: WebSocket 클라이언트 구독 동기화에 위임한다")
    void syncSubscriptions_delegates() {
        client.syncSubscriptions(Set.of("BTCUSDT"));

        then(webSocketClient).should().syncSubscriptions(Set.of("BTCUSDT"));
    }

    // ─────────────────────────────────────────────
    // 헬퍼 메서드
    // ─────────────────────────────────────────────

    private void givenExchangeInfo() throws Exception {
        given(feignClient.getExchangeInfo()).willReturn(objectMapper.readValue(
                new File(FILES + "BinanceMarketClient_getExchangeInfo.json"), BinanceExchangeInfoResponse.class));
    }

    /** 숫자·문자열이 섞인 캔들 배열이 문자열 목록으로 읽히는지도 함께 확인된다. */
    private List<List<String>> klineRows() throws Exception {
        return objectMapper.readValue(new File(FILES + "BinanceMarketClient_getKlines.json"),
                new TypeReference<List<List<String>>>() {});
    }

    private BinanceDepthResponse depth() throws Exception {
        return objectMapper.readValue(new File(FILES + "BinanceMarketClient_getDepth.json"), BinanceDepthResponse.class);
    }

    @SuppressWarnings("unchecked")
    private Consumer<BinanceWsTickerDTO> captureTickerCallback() {
        client.init();
        ArgumentCaptor<Consumer<BinanceWsTickerDTO>> captor = ArgumentCaptor.forClass(Consumer.class);
        then(webSocketClient).should().addTickerCallback(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private Consumer<BinanceWsKlineDTO> captureKlineCallback() {
        client.init();
        ArgumentCaptor<Consumer<BinanceWsKlineDTO>> captor = ArgumentCaptor.forClass(Consumer.class);
        then(webSocketClient).should().addKlineCallback(captor.capture());
        return captor.getValue();
    }

    private BinanceWsKlineDTO makeWsKline(boolean closed) {
        BinanceWsKlineDTO.Kline k = new BinanceWsKlineDTO.Kline();
        k.setStartTime(1761526740000L);
        k.setCloseTime(1761526799999L);
        k.setInterval("1m");
        k.setOpen("67010.2");
        k.setHigh("67100.0");
        k.setLow("67000.0");
        k.setClose("67090.9");
        k.setVolume("8.001");
        k.setClosed(closed);
        BinanceWsKlineDTO dto = new BinanceWsKlineDTO();
        dto.setSymbol("BTCUSDT");
        dto.setKline(k);
        return dto;
    }
}
