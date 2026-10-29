package com.coin.webcointrader.common.autotrade.service;

import com.coin.webcointrader.common.ExchangeClientRegistry;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.market.client.ExchangeMarketClient;
import com.coin.webcointrader.common.market.dto.PublicTradeDTO;
import com.coin.webcointrader.common.util.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VolumeMonitorServiceTest {

    // 구독 시작 시각 (epoch ms, 초 경계)
    private static final long START = 1_700_000_000_000L;

    @Mock
    private ExchangeClientRegistry adapterRegistry;

    @Mock
    private ExchangeMarketClient bybitMarket;

    private MutableClock clock;
    private VolumeMonitorService volumeMonitor;
    private Consumer<PublicTradeDTO> tradeListener;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        given(adapterRegistry.markets()).willReturn(List.of(bybitMarket));
        given(bybitMarket.getExchangeType()).willReturn(ExchangeType.BYBIT);
        clock = new MutableClock(START);
        volumeMonitor = new VolumeMonitorService(adapterRegistry, clock);
        volumeMonitor.init();
        ArgumentCaptor<Consumer<PublicTradeDTO>> captor = ArgumentCaptor.forClass(Consumer.class);
        then(bybitMarket).should().addTradeListener(captor.capture());
        tradeListener = captor.getValue();
    }

    private PublicTradeDTO trade(String symbol, long tradeTime, String qty) {
        return PublicTradeDTO.builder().symbol(symbol).tradeTime(tradeTime).qty(qty).build();
    }

    @Test
    @DisplayName("syncSubscriptions: 거래소별 감시 심볼로 체결 구독을 동기화한다")
    void sync_delegatesTradeSubscriptions() {
        volumeMonitor.syncSubscriptions(Map.of(ExchangeType.BYBIT, Set.of("BTCUSDT")));

        then(bybitMarket).should().syncTradeSubscriptions(Set.of("BTCUSDT"));
    }

    @Test
    @DisplayName("getVolume: 구독 후 60초가 지나 창이 차면 최근 60초 체결량 합을 반환한다")
    void getVolume_afterWindowFull() {
        volumeMonitor.syncSubscriptions(Map.of(ExchangeType.BYBIT, Set.of("BTCUSDT")));
        tradeListener.accept(trade("BTCUSDT", START + 10_000, "1.5"));
        tradeListener.accept(trade("BTCUSDT", START + 50_000, "2.5"));

        clock.advance(60_000);

        assertThat(volumeMonitor.getVolume(ExchangeType.BYBIT, "BTCUSDT")).isCloseTo(4.0, within(1e-9));
    }

    @Test
    @DisplayName("getVolume: 구독 후 60초가 안 됐으면(워밍업) null")
    void getVolume_nullDuringWarmup() {
        volumeMonitor.syncSubscriptions(Map.of(ExchangeType.BYBIT, Set.of("BTCUSDT")));
        tradeListener.accept(trade("BTCUSDT", START + 10_000, "1.5"));

        clock.advance(59_999);

        assertThat(volumeMonitor.getVolume(ExchangeType.BYBIT, "BTCUSDT")).isNull();
    }

    @Test
    @DisplayName("감시 대상이 아닌 심볼의 체결은 집계하지 않고, 대상에서 빠진 심볼은 집계를 버린다")
    void ignoresUnmonitoredSymbols() {
        volumeMonitor.syncSubscriptions(Map.of(ExchangeType.BYBIT, Set.of("BTCUSDT")));
        tradeListener.accept(trade("ETHUSDT", START + 1_000, "9"));
        clock.advance(60_000);
        assertThat(volumeMonitor.getVolume(ExchangeType.BYBIT, "ETHUSDT")).isNull();

        volumeMonitor.syncSubscriptions(Map.of());

        assertThat(volumeMonitor.getVolume(ExchangeType.BYBIT, "BTCUSDT")).isNull();
        then(bybitMarket).should().syncTradeSubscriptions(Set.of());
    }

    @Test
    @DisplayName("이미 감시 중인 심볼은 다시 동기화해도 집계를 이어간다 (워밍업 재시작 없음)")
    void resyncKeepsExistingWindow() {
        volumeMonitor.syncSubscriptions(Map.of(ExchangeType.BYBIT, Set.of("BTCUSDT")));
        tradeListener.accept(trade("BTCUSDT", START + 30_000, "3"));
        clock.advance(30_000);

        volumeMonitor.syncSubscriptions(Map.of(ExchangeType.BYBIT, Set.of("BTCUSDT")));
        clock.advance(30_000);

        assertThat(volumeMonitor.getVolume(ExchangeType.BYBIT, "BTCUSDT")).isCloseTo(3.0, within(1e-9));
    }

    @Test
    @DisplayName("수량이 숫자가 아닌 체결은 무시한다")
    void ignoresInvalidQty() {
        volumeMonitor.syncSubscriptions(Map.of(ExchangeType.BYBIT, Set.of("BTCUSDT")));
        tradeListener.accept(trade("BTCUSDT", START + 1_000, "abc"));
        tradeListener.accept(trade("BTCUSDT", START + 2_000, "1"));
        clock.advance(60_000);

        assertThat(volumeMonitor.getVolume(ExchangeType.BYBIT, "BTCUSDT")).isCloseTo(1.0, within(1e-9));
    }
}
