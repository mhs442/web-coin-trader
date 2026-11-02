package com.coin.webcointrader.common.market.service;

import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.ExchangeClientRegistry;
import com.coin.webcointrader.common.market.client.ExchangeMarketClient;
import com.coin.webcointrader.common.market.dto.InstrumentDTO;
import com.coin.webcointrader.common.market.dto.KlineDTO;
import com.coin.webcointrader.common.market.dto.OrderBookDTO;
import com.coin.webcointrader.common.market.dto.PriceTrendResponse;
import com.coin.webcointrader.common.market.dto.TickerDTO;
import com.coin.webcointrader.common.market.dto.TickerResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

/**
 * 거래소 중립 시세 서비스 단위 테스트.
 * 거래소 타입으로 시세 클라이언트를 골라 위임하는지, STOMP push를 연결하는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class MarketServiceTest {

    @Mock
    private ExchangeClientRegistry registry;

    @Mock
    private ExchangeMarketClient bybitMarket;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private MarketService marketService;

    @BeforeEach
    void setUp() {
        lenient().when(registry.market(ExchangeType.BYBIT)).thenReturn(bybitMarket);
        lenient().when(registry.markets()).thenReturn(List.of(bybitMarket));
        marketService = new MarketService(registry, messagingTemplate);
    }

    @Test
    @DisplayName("init: 각 거래소 티커 갱신을 거래소별 STOMP /topic/price.{exchange}.{symbol}로 push한다")
    @SuppressWarnings("unchecked")
    void init_pushesTickerToStomp() {
        given(bybitMarket.getExchangeType()).willReturn(ExchangeType.BYBIT);
        marketService.init();

        ArgumentCaptor<Consumer<TickerDTO>> captor = ArgumentCaptor.forClass(Consumer.class);
        then(bybitMarket).should().addTickerListener(captor.capture());
        captor.getValue().accept(TickerDTO.builder().symbol("BTCUSDT").lastPrice("50000").build());

        ArgumentCaptor<TickerResponse> payload = ArgumentCaptor.forClass(TickerResponse.class);
        then(messagingTemplate).should().convertAndSend(eq("/topic/price.BYBIT.BTCUSDT"), payload.capture());
        assertThat(payload.getValue().getLastPrice()).isEqualTo("50000");
        assertThat(payload.getValue().getFormattedSymbol()).isEqualTo("BTC / USDT");
    }

    @Test
    @DisplayName("refreshTickers: 모든 거래소의 티커 캐시를 갱신한다")
    void refreshTickers_allMarkets() {
        marketService.refreshTickers();

        then(bybitMarket).should().refreshTickers();
    }

    @Test
    @DisplayName("getTickers: 캐시가 있으면 갱신 없이 반환한다")
    void getTickers_cached() {
        given(bybitMarket.getTickers()).willReturn(List.of(TickerDTO.builder().symbol("BTCUSDT").build()));

        List<TickerDTO> tickers = marketService.getTickers(ExchangeType.BYBIT);

        assertThat(tickers).extracting(TickerDTO::getSymbol).containsExactly("BTCUSDT");
        then(bybitMarket).should(never()).refreshTickers();
    }

    @Test
    @DisplayName("getTickers: 캐시가 비어 있으면 한 번 갱신 후 반환한다")
    void getTickers_emptyRefreshes() {
        given(bybitMarket.getTickers())
                .willReturn(List.of())
                .willReturn(List.of(TickerDTO.builder().symbol("ETHUSDT").build()));

        List<TickerDTO> tickers = marketService.getTickers(ExchangeType.BYBIT);

        then(bybitMarket).should().refreshTickers();
        assertThat(tickers).extracting(TickerDTO::getSymbol).containsExactly("ETHUSDT");
    }

    @Test
    @DisplayName("getDisplayTickers: 대시보드 목록은 다른 거래소가 등록돼 있어도 Bybit 목록만 반환한다")
    void getDisplayTickers_alwaysBybit() {
        given(bybitMarket.getTickers()).willReturn(List.of(TickerDTO.builder().symbol("BTCUSDT").build()));

        List<TickerDTO> tickers = marketService.getDisplayTickers();

        assertThat(tickers).extracting(TickerDTO::getSymbol).containsExactly("BTCUSDT");
        then(registry).should(never()).market(ExchangeType.BINANCE);
    }

    @Test
    @DisplayName("getTicker·isWsActive: 해당 거래소 시세 클라이언트에 위임한다")
    void getTickerAndWsActive_delegate() {
        TickerDTO ticker = TickerDTO.builder().symbol("BTCUSDT").lastPrice("1").build();
        given(bybitMarket.getTicker("BTCUSDT")).willReturn(ticker);
        given(bybitMarket.isWsActive()).willReturn(true);

        assertThat(marketService.getTicker(ExchangeType.BYBIT, "BTCUSDT")).isSameAs(ticker);
        assertThat(marketService.isWsActive(ExchangeType.BYBIT)).isTrue();
    }

    @Test
    @DisplayName("getKlines: 최근 200개 캔들을 조회한다")
    void getKlines_limit200() {
        List<KlineDTO> klines = List.of(KlineDTO.builder().startTime(1L).build());
        given(bybitMarket.getKlines("BTCUSDT", "60", 200)).willReturn(klines);

        assertThat(marketService.getKlines(ExchangeType.BYBIT, "BTCUSDT", "60")).isSameAs(klines);
    }

    @Test
    @DisplayName("getOrderBook: 상위 50단계 호가를 조회한다")
    void getOrderBook_depth50() {
        OrderBookDTO book = OrderBookDTO.builder().symbol("BTCUSDT").build();
        given(bybitMarket.getOrderBook("BTCUSDT", 50)).willReturn(book);

        assertThat(marketService.getOrderBook(ExchangeType.BYBIT, "BTCUSDT")).isSameAs(book);
    }

    @Test
    @DisplayName("convertUsdtToQty: 해당 거래소 시세 클라이언트에 위임한다")
    void convertUsdtToQty_delegates() {
        given(bybitMarket.convertUsdtToQty(eq("BTCUSDT"), any(), any())).willReturn("0.003");

        String qty = marketService.convertUsdtToQty(ExchangeType.BYBIT, "BTCUSDT",
                new BigDecimal("100"), new BigDecimal("30000"));

        assertThat(qty).isEqualTo("0.003");
    }

    @Test
    @DisplayName("getTickSize: 거래 규칙의 tickSize를 반환하고, 규칙이 없으면 null")
    void getTickSize() {
        given(bybitMarket.getInstrument("BTCUSDT"))
                .willReturn(InstrumentDTO.builder().symbol("BTCUSDT").tickSize("0.10").build());
        given(bybitMarket.getInstrument("XYZUSDT")).willReturn(null);

        assertThat(marketService.getTickSize(ExchangeType.BYBIT, "BTCUSDT")).isEqualTo("0.10");
        assertThat(marketService.getTickSize(ExchangeType.BYBIT, "XYZUSDT")).isNull();
    }

    // ─────────────────────────────────────────────
    // 최근 1시간 추세
    // ─────────────────────────────────────────────

    /** 해당 심볼·현재가가 시세 목록에 있는 상태로 한 번 저장한다. */
    private void record(String symbol, String lastPrice) {
        given(bybitMarket.getTickers())
                .willReturn(List.of(TickerDTO.builder().symbol(symbol).lastPrice(lastPrice).build()));
        marketService.recordPriceTrends();
    }

    /** 심볼의 저장된 가격을 숫자 문자열로 돌려준다. 해당 심볼이 없으면 null. */
    private List<String> trendOf(String symbol) {
        return marketService.getPriceTrends().stream()
                .filter(trend -> trend.getSymbol().equals(symbol))
                .findFirst()
                .map(PriceTrendResponse::getPrices)
                .map(prices -> prices.stream().map(BigDecimal::toPlainString).toList())
                .orElse(null);
    }

    @Test
    @DisplayName("recordPriceTrends: 현재가를 저장 순서대로 쌓는다")
    void recordPriceTrends_keepsOrder() {
        record("BTCUSDT", "100");
        record("BTCUSDT", "101");
        record("BTCUSDT", "99");

        assertThat(trendOf("BTCUSDT")).containsExactly("100", "101", "99");
    }

    @Test
    @DisplayName("recordPriceTrends: 60개를 넘으면 가장 오래된 값부터 버린다")
    void recordPriceTrends_keepsLatestSixty() {
        for (int i = 1; i <= 61; i++) {
            record("BTCUSDT", String.valueOf(i));
        }

        List<String> prices = trendOf("BTCUSDT");
        assertThat(prices).hasSize(60);
        assertThat(prices.get(0)).isEqualTo("2");
        assertThat(prices.get(59)).isEqualTo("61");
    }

    @Test
    @DisplayName("recordPriceTrends: 시세 목록에서 사라진 코인은 기록에서 제거한다")
    void recordPriceTrends_removesDelistedSymbol() {
        record("OLDUSDT", "1");
        record("BTCUSDT", "100");

        assertThat(trendOf("OLDUSDT")).isNull();
        assertThat(trendOf("BTCUSDT")).containsExactly("100");
    }

    @Test
    @DisplayName("recordPriceTrends: 시세 목록이 비면 일시 오류로 보고 기존 기록을 유지한다")
    void recordPriceTrends_keepsHistoryWhenTickersEmpty() {
        record("BTCUSDT", "100");

        given(bybitMarket.getTickers()).willReturn(List.of());
        marketService.recordPriceTrends();

        assertThat(trendOf("BTCUSDT")).containsExactly("100");
    }

    @Test
    @DisplayName("recordPriceTrends: 숫자가 아니거나 0 이하인 현재가는 건너뛰고 기존 기록을 유지한다")
    void recordPriceTrends_skipsInvalidPrice() {
        record("BTCUSDT", "100");
        record("BTCUSDT", "abc");
        record("BTCUSDT", "");
        record("BTCUSDT", null);
        record("BTCUSDT", "0");
        record("BTCUSDT", "101");

        assertThat(trendOf("BTCUSDT")).containsExactly("100", "101");
    }

    @Test
    @DisplayName("getPriceTrends: 저장 전에는 빈 목록을 반환한다")
    void getPriceTrends_emptyBeforeRecord() {
        assertThat(marketService.getPriceTrends()).isEmpty();
    }
}
