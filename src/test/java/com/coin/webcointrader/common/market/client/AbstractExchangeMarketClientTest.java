package com.coin.webcointrader.common.market.client;

import com.coin.webcointrader.common.util.MutableClock;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.market.dto.InstrumentDTO;
import com.coin.webcointrader.common.market.dto.KlineDTO;
import com.coin.webcointrader.common.market.dto.OrderBookDTO;
import com.coin.webcointrader.common.market.dto.TickerDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시세 클라이언트 공통 처리(캐시·수량 변환·리스너 팬아웃·WS 활성 판정) 단위 테스트.
 */
class AbstractExchangeMarketClientTest {

    private MutableClock clock;
    private FakeMarketClient client;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(1_000_000L);
        client = new FakeMarketClient(clock);
    }

    @Test
    @DisplayName("refreshTickers - 조회 결과를 캐시하고 getTickers로 반환")
    void refreshTickers_cachesResult() {
        client.tickers = List.of(ticker("BTCUSDT", "100"), ticker("ETHUSDT", "10"));

        client.refreshTickers();

        assertThat(client.getTickers()).extracting(TickerDTO::getSymbol).containsExactly("BTCUSDT", "ETHUSDT");
    }

    @Test
    @DisplayName("refreshTickers - 조회 실패 시 기존 캐시 유지")
    void refreshTickers_failureKeepsCache() {
        client.tickers = List.of(ticker("BTCUSDT", "100"));
        client.refreshTickers();

        client.failTickers = true;
        client.refreshTickers();

        assertThat(client.getTickers()).hasSize(1);
    }

    @Test
    @DisplayName("getInstrument - 캐시 미스면 갱신 후 반환, 이후에는 캐시 사용")
    void getInstrument_refreshOnMiss() {
        client.instruments = List.of(instrument("BTCUSDT", "0.001"));

        assertThat(client.getInstrument("BTCUSDT").getQtyStep()).isEqualTo("0.001");
        assertThat(client.getInstrument("BTCUSDT").getQtyStep()).isEqualTo("0.001");
        assertThat(client.instrumentFetchCount).isEqualTo(1);
    }

    @Test
    @DisplayName("convertUsdtToQty - qtyStep 단위로 내림")
    void convertUsdtToQty_floorsToStep() {
        client.instruments = List.of(instrument("BTCUSDT", "0.001"));

        // 100 / 30000 = 0.00333.. → 0.003
        String qty = client.convertUsdtToQty("BTCUSDT", new BigDecimal("100"), new BigDecimal("30000"));

        assertThat(qty).isEqualTo("0.003");
    }

    @Test
    @DisplayName("convertUsdtToQty - 최소 단위 미달이면 \"0\"")
    void convertUsdtToQty_belowStepReturnsZero() {
        client.instruments = List.of(instrument("BTCUSDT", "0.001"));

        assertThat(client.convertUsdtToQty("BTCUSDT", new BigDecimal("10"), new BigDecimal("30000"))).isEqualTo("0");
    }

    @Test
    @DisplayName("convertUsdtToQty - 거래 규칙이 없으면 null")
    void convertUsdtToQty_noInstrumentReturnsNull() {
        assertThat(client.convertUsdtToQty("XYZUSDT", new BigDecimal("10"), new BigDecimal("1"))).isNull();
    }

    @Test
    @DisplayName("publishTicker - 최신 티커 저장, 리스너 전달, 한 리스너 예외가 다른 리스너를 막지 않음")
    void publishTicker_fanOut() {
        List<String> received = new ArrayList<>();
        client.addTickerListener(t -> { throw new IllegalStateException("boom"); });
        client.addTickerListener(t -> received.add(t.getLastPrice()));

        client.publish(ticker("BTCUSDT", "123"));

        assertThat(received).containsExactly("123");
        assertThat(client.getTicker("BTCUSDT").getLastPrice()).isEqualTo("123");
    }

    @Test
    @DisplayName("publishTicker - 현재가가 그대로인 갱신은 리스너에 전달하지 않지만 최신 티커·활성 시각은 갱신")
    void publishTicker_samePriceSkipsListeners() {
        List<String> received = new ArrayList<>();
        client.addTickerListener(t -> received.add(t.getLastPrice()));

        client.publish(ticker("BTCUSDT", "123"));
        clock.advance(10_000);
        // 거래량만 바뀐 갱신 (현재가 동일)
        client.publish(TickerDTO.builder().symbol("BTCUSDT").lastPrice("123").volume24h("999").build());

        assertThat(received).containsExactly("123");
        assertThat(client.getTicker("BTCUSDT").getVolume24h()).isEqualTo("999");
        assertThat(client.isWsActive()).isTrue();
    }

    @Test
    @DisplayName("publishTicker - 현재가가 없는 티커는 리스너에 전달하지 않음")
    void publishTicker_nullPriceSkipsListeners() {
        List<String> received = new ArrayList<>();
        client.addTickerListener(t -> received.add(t.getLastPrice()));

        client.publish(TickerDTO.builder().symbol("BTCUSDT").volume24h("1").build());

        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("publishKline - 마감된 봉만 리스너에 전달")
    void publishKline_onlyConfirmed() {
        List<Long> received = new ArrayList<>();
        client.addKlineListener(k -> received.add(k.getStartTime()));

        client.publish(KlineDTO.builder().symbol("BTCUSDT").startTime(1L).confirmed(false).build());
        client.publish(KlineDTO.builder().symbol("BTCUSDT").startTime(2L).confirmed(true).build());

        assertThat(received).containsExactly(2L);
    }

    @Test
    @DisplayName("isWsActive - 마지막 티커 수신 후 5초 이내면 true, 초과면 false")
    void isWsActive_threshold() {
        assertThat(client.isWsActive()).isFalse();

        client.publish(ticker("BTCUSDT", "1"));
        clock.advance(4_999);
        assertThat(client.isWsActive()).isTrue();

        clock.advance(2);
        assertThat(client.isWsActive()).isFalse();
    }

    private static TickerDTO ticker(String symbol, String lastPrice) {
        return TickerDTO.builder().symbol(symbol).lastPrice(lastPrice).build();
    }

    private static InstrumentDTO instrument(String symbol, String qtyStep) {
        return InstrumentDTO.builder().symbol(symbol).qtyStep(qtyStep).tickSize("0.1").build();
    }

    /** 테스트용 시세 클라이언트 (거래소 조회를 필드 값으로 대체) */
    static class FakeMarketClient extends AbstractExchangeMarketClient {
        List<TickerDTO> tickers = List.of();
        List<InstrumentDTO> instruments = List.of();
        boolean failTickers;
        int instrumentFetchCount;

        FakeMarketClient(Clock clock) {
            super(clock);
        }

        void publish(TickerDTO t) { publishTicker(t); }
        void publish(KlineDTO k) { publishKline(k); }

        @Override public ExchangeType getExchangeType() { return ExchangeType.BYBIT; }

        @Override protected List<TickerDTO> fetchTickers() {
            if (failTickers) throw new IllegalStateException("fail");
            return tickers;
        }

        @Override protected List<InstrumentDTO> fetchInstruments() {
            instrumentFetchCount++;
            return instruments;
        }

        @Override public List<KlineDTO> getKlines(String symbol, String interval, int limit) { return List.of(); }
        @Override public OrderBookDTO getOrderBook(String symbol, int depth) { return null; }
        @Override public void syncSubscriptions(Set<String> symbols) { }
    }
}
