package com.coin.webcointrader.market.service;

import com.coin.webcointrader.client.market.MarketClient;
import com.coin.webcointrader.client.market.dto.WebSocketTickerDTO;
import com.coin.webcointrader.common.dto.response.FindTickerResponse;
import com.coin.webcointrader.common.dto.response.GetKlineResponse;
import com.coin.webcointrader.common.dto.response.OrderBookResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class MarketServiceTest {

    @InjectMocks
    private MarketService marketService;

    @Mock
    private MarketClient marketClient;

    @BeforeEach
    void clearCache() {
        // 캐시를 null로 초기화
        ReflectionTestUtils.setField(marketService, "cachedTickers",
                new java.util.concurrent.atomic.AtomicReference<>(null));
    }

    // ─────────────────────────────────────────────
    // refreshTickersCache
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("refreshTickersCache: USDT로 끝나지 않는 심볼은 필터링한다")
    void refreshTickersCache_filtersNonUsdt() {
        // given
        FindTickerResponse response = makeTickerResponse(List.of(
                makeTicker("BTCUSDT", "1000.0"),
                makeTicker("ETHUSDT", "500.0"),
                makeTicker("BTCETH", "2.0")  // USDT 아님 → 제거되어야 함
        ));
        given(marketClient.getTickers("linear")).willReturn(ResponseEntity.ok(response));

        // when
        marketService.refreshTickersCache();

        // then
        FindTickerResponse cached = marketService.getTickers();
        assertThat(cached.getResult().getList()).hasSize(2);
        assertThat(cached.getResult().getList())
                .noneMatch(t -> t.getSymbol().equals("BTCETH"));
    }

    @Test
    @DisplayName("refreshTickersCache: 거래량(volume24h) 기준 내림차순 정렬한다")
    void refreshTickersCache_sortsByVolume() {
        // given
        FindTickerResponse response = makeTickerResponse(List.of(
                makeTicker("ETHUSDT", "500.0"),
                makeTicker("BTCUSDT", "10000.0"),
                makeTicker("SOLUSDT", "2000.0")
        ));
        given(marketClient.getTickers("linear")).willReturn(ResponseEntity.ok(response));

        // when
        marketService.refreshTickersCache();

        // then
        List<FindTickerResponse.TickerInfo> list = marketService.getTickers().getResult().getList();
        assertThat(list.get(0).getSymbol()).isEqualTo("BTCUSDT");   // 10000.0 → 1등
        assertThat(list.get(1).getSymbol()).isEqualTo("SOLUSDT");   // 2000.0 → 2등
        assertThat(list.get(2).getSymbol()).isEqualTo("ETHUSDT");   // 500.0  → 3등
    }

    // ─────────────────────────────────────────────
    // getTickers
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("getTickers: 캐시가 있으면 캐시를 반환하고 API를 호출하지 않는다")
    void getTickers_returnsCacheIfPresent() {
        // given - 캐시 직접 설정
        FindTickerResponse cached = makeTickerResponse(List.of(makeTicker("BTCUSDT", "5000.0")));
        ReflectionTestUtils.setField(marketService, "cachedTickers",
                new java.util.concurrent.atomic.AtomicReference<>(cached));

        // when
        FindTickerResponse result = marketService.getTickers();

        // then
        assertThat(result).isSameAs(cached);
        then(marketClient).should(never()).getTickers(anyString());
    }

    @Test
    @DisplayName("getTickers: 캐시가 없으면 API를 호출하여 갱신 후 반환한다")
    void getTickers_refreshesIfCacheIsEmpty() {
        // given
        FindTickerResponse response = makeTickerResponse(List.of(makeTicker("BTCUSDT", "5000.0")));
        given(marketClient.getTickers("linear")).willReturn(ResponseEntity.ok(response));

        // when
        FindTickerResponse result = marketService.getTickers();

        // then
        assertThat(result).isNotNull();
        then(marketClient).should(times(1)).getTickers("linear");
    }

    // ─────────────────────────────────────────────
    // onTickerUpdate (WebSocket)
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("onTickerUpdate: snapshot 메시지를 수신하면 wsTickerMap에 전체 데이터를 저장한다")
    void onTickerUpdate_snapshotStoresFullData() {
        // given
        WebSocketTickerDTO dto = makeWsTickerDTO("snapshot", "BTCUSDT", "50000.00", "10000.0");

        // when
        marketService.onTickerUpdate(dto);

        // then
        FindTickerResponse.TickerInfo stored = marketService.getWsTicker("BTCUSDT");
        assertThat(stored).isNotNull();
        assertThat(stored.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(stored.getLastPrice()).isEqualTo("50000.00");
        assertThat(stored.getVolume24h()).isEqualTo("10000.0");
    }

    @Test
    @DisplayName("onTickerUpdate: delta 메시지를 수신하면 변경된 필드만 병합한다")
    void onTickerUpdate_deltaMergesChangedFieldsOnly() {
        // given - 먼저 snapshot으로 전체 데이터 설정
        WebSocketTickerDTO snapshot = makeWsTickerDTO("snapshot", "BTCUSDT", "50000.00", "10000.0");
        marketService.onTickerUpdate(snapshot);

        // delta 메시지: lastPrice만 변경
        WebSocketTickerDTO delta = new WebSocketTickerDTO();
        delta.setTopic("tickers.BTCUSDT");
        delta.setType("delta");
        WebSocketTickerDTO.TickerData deltaData = new WebSocketTickerDTO.TickerData();
        deltaData.setSymbol("BTCUSDT");
        deltaData.setLastPrice("50100.00"); // 가격만 변경
        delta.setData(deltaData);
        delta.setTs(1699999801000L);

        // when
        marketService.onTickerUpdate(delta);

        // then - lastPrice만 변경되고 나머지는 유지
        FindTickerResponse.TickerInfo stored = marketService.getWsTicker("BTCUSDT");
        assertThat(stored.getLastPrice()).isEqualTo("50100.00");   // 변경됨
        assertThat(stored.getVolume24h()).isEqualTo("10000.0");     // 유지됨
    }

    @Test
    @DisplayName("onTickerUpdate: delta 메시지인데 기존 데이터가 없으면 새로 생성한다")
    void onTickerUpdate_deltaCreatesNewIfNoExisting() {
        // given
        WebSocketTickerDTO delta = makeWsTickerDTO("delta", "ETHUSDT", "3000.00", null);

        // when
        marketService.onTickerUpdate(delta);

        // then
        FindTickerResponse.TickerInfo stored = marketService.getWsTicker("ETHUSDT");
        assertThat(stored).isNotNull();
        assertThat(stored.getLastPrice()).isEqualTo("3000.00");
    }

    @Test
    @DisplayName("onTickerUpdate: null DTO나 null data는 무시한다")
    void onTickerUpdate_ignoresNullInput() {
        // when & then - 예외 없이 무시
        assertThatCode(() -> marketService.onTickerUpdate(null)).doesNotThrowAnyException();

        WebSocketTickerDTO emptyDto = new WebSocketTickerDTO();
        assertThatCode(() -> marketService.onTickerUpdate(emptyDto)).doesNotThrowAnyException();
    }

    // ─────────────────────────────────────────────
    // 가격 리스너
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("onTickerUpdate: 가격 변동 시 등록된 리스너에 알림한다")
    void onTickerUpdate_notifiesPriceListeners() {
        // given
        AtomicReference<String> receivedSymbol = new AtomicReference<>();
        AtomicReference<String> receivedPrice = new AtomicReference<>();
        marketService.addPriceListener((symbol, price) -> {
            receivedSymbol.set(symbol);
            receivedPrice.set(price);
        });

        WebSocketTickerDTO dto = makeWsTickerDTO("snapshot", "BTCUSDT", "50000.00", "10000.0");

        // when
        marketService.onTickerUpdate(dto);

        // then
        assertThat(receivedSymbol.get()).isEqualTo("BTCUSDT");
        assertThat(receivedPrice.get()).isEqualTo("50000.00");
    }

    @Test
    @DisplayName("onTickerUpdate: 리스너에서 예외가 발생해도 다른 리스너는 정상 호출된다")
    void onTickerUpdate_listenerExceptionDoesNotAffectOthers() {
        // given
        AtomicReference<String> secondListenerPrice = new AtomicReference<>();

        // 첫 번째 리스너: 예외 발생
        marketService.addPriceListener((symbol, price) -> {
            throw new RuntimeException("테스트 예외");
        });
        // 두 번째 리스너: 정상 처리
        marketService.addPriceListener((symbol, price) -> {
            secondListenerPrice.set(price);
        });

        WebSocketTickerDTO dto = makeWsTickerDTO("snapshot", "BTCUSDT", "50000.00", "10000.0");

        // when
        marketService.onTickerUpdate(dto);

        // then - 두 번째 리스너 정상 호출
        assertThat(secondListenerPrice.get()).isEqualTo("50000.00");
    }

    // ─────────────────────────────────────────────
    // getKline
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("getKline: symbol과 interval을 포함하여 K-라인 데이터를 반환한다")
    void getKline_success() {
        // given
        GetKlineResponse response = new GetKlineResponse();
        given(marketClient.getKline("linear", "BTCUSDT", "60", 200))
                .willReturn(ResponseEntity.ok(response));

        // when
        GetKlineResponse result = marketService.getKline("BTCUSDT", "60");

        // then
        assertThat(result).isNotNull();
        then(marketClient).should().getKline("linear", "BTCUSDT", "60", 200);
    }

    // ─────────────────────────────────────────────
    // getOrderBook
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("getOrderBook: symbol을 포함하여 호가창 데이터를 반환한다")
    void getOrderBook_success() {
        // given
        OrderBookResponse response = new OrderBookResponse();
        given(marketClient.getOrderBook("linear", "BTCUSDT", 50))
                .willReturn(ResponseEntity.ok(response));

        // when
        OrderBookResponse result = marketService.getOrderBook("BTCUSDT");

        // then
        assertThat(result).isNotNull();
        then(marketClient).should().getOrderBook("linear", "BTCUSDT", 50);
    }

    // ─────────────────────────────────────────────
    // 헬퍼 메서드
    // ─────────────────────────────────────────────

    private FindTickerResponse makeTickerResponse(List<FindTickerResponse.TickerInfo> tickers) {
        FindTickerResponse response = new FindTickerResponse();
        FindTickerResponse.Result result = new FindTickerResponse.Result();
        result.setCategory("linear");
        result.setList(new ArrayList<>(tickers));
        response.setResult(result);
        return response;
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

    /**
     * WebSocket 티커 DTO 테스트 헬퍼 메서드.
     */
    private WebSocketTickerDTO makeWsTickerDTO(String type, String symbol, String lastPrice, String volume24h) {
        WebSocketTickerDTO dto = new WebSocketTickerDTO();
        dto.setTopic("tickers." + symbol);
        dto.setType(type);
        dto.setTs(1699999800000L);

        WebSocketTickerDTO.TickerData data = new WebSocketTickerDTO.TickerData();
        data.setSymbol(symbol);
        data.setLastPrice(lastPrice);
        data.setVolume24h(volume24h);
        dto.setData(data);

        return dto;
    }
}
