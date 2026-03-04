package com.coin.webcointrader.autotrade.service;

import com.coin.webcointrader.autotrade.dto.AutoTradeSessionDTO;
import com.coin.webcointrader.autotrade.repository.QueueRepository;
import com.coin.webcointrader.autotrade.repository.TradeHistoryRepository;
import com.coin.webcointrader.client.market.BybitWebSocketClient;
import com.coin.webcointrader.common.dto.response.FindTickerResponse;
import com.coin.webcointrader.common.entity.Queue;
import com.coin.webcointrader.common.entity.QueueStep;
import com.coin.webcointrader.common.entity.Side;
import com.coin.webcointrader.market.service.MarketService;
import com.coin.webcointrader.trade.service.TradeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class AutoTradeServiceTest {

    @InjectMocks
    private AutoTradeService autoTradeService;

    @Mock
    private QueueRepository queueRepository;

    @Mock
    private TradeHistoryRepository tradeHistoryRepository;

    @Mock
    private TradeService tradeService;

    @Mock
    private MarketService marketService;

    @Mock
    private BybitWebSocketClient bybitWebSocketClient;

    // ─────────────────────────────────────────────
    // init (가격 리스너 등록)
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("init: 시작 시 MarketService에 가격 리스너를 등록한다")
    void init_registersPriceListener() {
        // when
        autoTradeService.init();

        // then
        then(marketService).should(times(1)).addPriceListener(any());
    }

    // ─────────────────────────────────────────────
    // syncSession
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("syncSession: 활성 큐가 있으면 새 세션을 생성한다")
    void syncSession_createsNewSession() {
        // given
        Long userId = 1L;
        String symbol = "BTCUSDT";
        Queue q = makeQueue(1L, userId, symbol);
        given(queueRepository.findByUserIdAndSymbolAndUseYnAndDelYnOrderBySortOrderAsc(
                userId, symbol, "Y", "N"))
                .willReturn(List.of(q));

        // when
        autoTradeService.syncSession(userId, symbol);

        // then
        assertThat(autoTradeService.isActive(userId, symbol)).isTrue();
        assertThat(autoTradeService.getSession(userId, symbol)).isNotNull();
    }

    @Test
    @DisplayName("syncSession: 기존 세션이 있고 활성 큐가 있으면 세션을 갱신한다")
    void syncSession_updatesExistingSession() {
        // given
        Long userId = 1L;
        String symbol = "BTCUSDT";
        Queue q1 = makeQueue(1L, userId, symbol);
        given(queueRepository.findByUserIdAndSymbolAndUseYnAndDelYnOrderBySortOrderAsc(
                userId, symbol, "Y", "N"))
                .willReturn(List.of(q1));

        autoTradeService.syncSession(userId, symbol); // 세션 생성

        Queue q2 = makeQueue(2L, userId, symbol);
        given(queueRepository.findByUserIdAndSymbolAndUseYnAndDelYnOrderBySortOrderAsc(
                userId, symbol, "Y", "N"))
                .willReturn(List.of(q1, q2));

        // when
        autoTradeService.syncSession(userId, symbol); // 세션 갱신

        // then
        assertThat(autoTradeService.getSession(userId, symbol).getQueues()).hasSize(2);
    }

    @Test
    @DisplayName("syncSession: 활성 큐가 없으면 세션을 제거한다")
    void syncSession_removesSessionWhenNoActiveQueues() {
        // given
        Long userId = 1L;
        String symbol = "BTCUSDT";
        Queue q = makeQueue(1L, userId, symbol);

        given(queueRepository.findByUserIdAndSymbolAndUseYnAndDelYnOrderBySortOrderAsc(
                userId, symbol, "Y", "N"))
                .willReturn(List.of(q));
        autoTradeService.syncSession(userId, symbol); // 세션 생성

        given(queueRepository.findByUserIdAndSymbolAndUseYnAndDelYnOrderBySortOrderAsc(
                userId, symbol, "Y", "N"))
                .willReturn(List.of()); // 빈 큐

        // when
        autoTradeService.syncSession(userId, symbol);

        // then
        assertThat(autoTradeService.isActive(userId, symbol)).isFalse();
    }

    @Test
    @DisplayName("syncSession: WebSocket 구독을 동기화한다")
    void syncSession_syncsWebSocketSubscriptions() {
        // given
        Long userId = 1L;
        String symbol = "BTCUSDT";
        Queue q = makeQueue(1L, userId, symbol);
        given(queueRepository.findByUserIdAndSymbolAndUseYnAndDelYnOrderBySortOrderAsc(
                userId, symbol, "Y", "N"))
                .willReturn(List.of(q));

        // when
        autoTradeService.syncSession(userId, symbol);

        // then - WebSocket 구독 동기화 호출 확인
        ArgumentCaptor<Set<String>> captor = ArgumentCaptor.forClass(Set.class);
        then(bybitWebSocketClient).should(atLeastOnce()).syncSubscriptions(captor.capture());
        assertThat(captor.getValue()).contains("BTCUSDT");
    }

    @Test
    @DisplayName("syncSession: 세션 제거 시 WebSocket 구독도 동기화된다")
    void syncSession_removeSession_syncsEmptySubscriptions() {
        // given
        Long userId = 1L;
        String symbol = "BTCUSDT";
        Queue q = makeQueue(1L, userId, symbol);

        given(queueRepository.findByUserIdAndSymbolAndUseYnAndDelYnOrderBySortOrderAsc(
                userId, symbol, "Y", "N"))
                .willReturn(List.of(q));
        autoTradeService.syncSession(userId, symbol);

        given(queueRepository.findByUserIdAndSymbolAndUseYnAndDelYnOrderBySortOrderAsc(
                userId, symbol, "Y", "N"))
                .willReturn(List.of());

        // when
        autoTradeService.syncSession(userId, symbol);

        // then - 빈 세트로 동기화
        ArgumentCaptor<Set<String>> captor = ArgumentCaptor.forClass(Set.class);
        then(bybitWebSocketClient).should(atLeast(2)).syncSubscriptions(captor.capture());
        // 마지막 호출에서 빈 세트
        assertThat(captor.getAllValues().get(captor.getAllValues().size() - 1)).isEmpty();
    }

    // ─────────────────────────────────────────────
    // isActive / getSession
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("isActive: 세션이 없으면 false를 반환한다")
    void isActive_returnsFalseWhenNoSession() {
        assertThat(autoTradeService.isActive(1L, "BTCUSDT")).isFalse();
    }

    @Test
    @DisplayName("getSession: 세션이 없으면 null을 반환한다")
    void getSession_returnsNullWhenNotFound() {
        assertThat(autoTradeService.getSession(1L, "BTCUSDT")).isNull();
    }

    // ─────────────────────────────────────────────
    // tick (fallback 스케줄러)
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("tick: 활성 세션이 없으면 marketService를 호출하지 않는다")
    void tick_doesNothingWhenNoSessions() {
        // when
        autoTradeService.tick();

        // then
        then(marketService).should(never()).getTickers();
    }

    @Test
    @DisplayName("tick: getTickers 응답이 null이면 처리를 중단한다")
    void tick_stopsWhenTickersNull() {
        // given - 세션 생성 후 getTickers가 null 반환
        Long userId = 1L;
        String symbol = "BTCUSDT";
        Queue q = makeQueue(1L, userId, symbol);
        given(queueRepository.findByUserIdAndSymbolAndUseYnAndDelYnOrderBySortOrderAsc(
                userId, symbol, "Y", "N"))
                .willReturn(List.of(q));
        autoTradeService.syncSession(userId, symbol);

        given(marketService.getTickers()).willReturn(null);

        // when & then - 예외 없이 처리 중단
        assertThatCode(() -> autoTradeService.tick()).doesNotThrowAnyException();
        then(tradeService).should(never()).placeOrder(any(), any());
    }

    // ─────────────────────────────────────────────
    // processSession (신호 처리 - REST fallback)
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("processSession: 직전 가격이 없으면 초기 가격만 설정하고 주문하지 않는다")
    void processSession_setsInitialPriceWithoutOrder() {
        // given
        Long userId = 1L;
        String symbol = "BTCUSDT";
        Queue q = makeQueue(1L, userId, symbol, Side.LONG);
        given(queueRepository.findByUserIdAndSymbolAndUseYnAndDelYnOrderBySortOrderAsc(
                userId, symbol, "Y", "N"))
                .willReturn(List.of(q));
        autoTradeService.syncSession(userId, symbol);

        FindTickerResponse tickers = makeTickerResponse(symbol, "50000.00");
        given(marketService.getTickers()).willReturn(tickers);

        // when
        autoTradeService.tick(); // 초기 가격 설정

        // then - 주문 없음
        then(tradeService).should(never()).placeOrder(any(), any());
        assertThat(autoTradeService.getSession(userId, symbol).getPreviousPrice())
                .isEqualTo("50000.00");
    }

    @Test
    @DisplayName("processSession: 현재가 > 직전가(LONG 신호)이고 단계가 LONG이면 주문을 실행한다")
    void processSession_executesOrderOnLongSignal() {
        // given
        Long userId = 1L;
        String symbol = "BTCUSDT";
        Queue q = makeQueue(1L, userId, symbol, Side.LONG);
        given(queueRepository.findByUserIdAndSymbolAndUseYnAndDelYnOrderBySortOrderAsc(
                userId, symbol, "Y", "N"))
                .willReturn(List.of(q));
        autoTradeService.syncSession(userId, symbol);

        // 1tick: 초기 가격 설정
        given(marketService.getTickers()).willReturn(makeTickerResponse(symbol, "50000.00"));
        autoTradeService.tick();

        // 2tick: 가격 상승 → LONG 신호 → 단계 LONG과 일치 → 주문
        given(marketService.getTickers()).willReturn(makeTickerResponse(symbol, "50100.00"));
        given(tradeHistoryRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        // when
        autoTradeService.tick();

        // then
        then(tradeService).should(times(1)).placeOrder(any(), eq(userId));
    }

    @Test
    @DisplayName("processSession: LONG 신호이지만 단계가 SHORT이면 다른 큐로 전환을 시도한다")
    void processSession_switchesQueueOnSignalMismatch() {
        // given
        Long userId = 1L;
        String symbol = "BTCUSDT";
        Queue longQ = makeQueue(1L, userId, symbol, Side.LONG, 2);
        Queue shortQ = makeQueue(2L, userId, symbol, Side.SHORT);

        given(queueRepository.findByUserIdAndSymbolAndUseYnAndDelYnOrderBySortOrderAsc(
                userId, symbol, "Y", "N"))
                .willReturn(List.of(shortQ, longQ)); // shortQ가 currentQueue
        autoTradeService.syncSession(userId, symbol);

        // 1tick: 초기 가격
        given(marketService.getTickers()).willReturn(makeTickerResponse(symbol, "50000.00"));
        autoTradeService.tick();

        // 2tick: 가격 상승 → LONG 신호 → shortQ와 불일치 → longQ로 전환 후 주문
        given(marketService.getTickers()).willReturn(makeTickerResponse(symbol, "50100.00"));
        given(tradeHistoryRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        // when
        autoTradeService.tick();

        // then - longQ로 전환 후 주문 실행
        then(tradeService).should(times(1)).placeOrder(any(), eq(userId));
        assertThat(autoTradeService.getSession(userId, symbol).getCurrentQueueIndex()).isEqualTo(1);
    }

    // ─────────────────────────────────────────────
    // onPriceUpdate (WebSocket 이벤트 핸들러)
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("onPriceUpdate: 활성 세션이 없으면 아무 작업도 하지 않는다")
    void onPriceUpdate_doesNothingWhenNoSessions() {
        // when & then - 예외 없이 처리
        assertThatCode(() -> autoTradeService.onPriceUpdate("BTCUSDT", "50000.00"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("onPriceUpdate: 해당 심볼의 세션에 초기 가격을 설정한다")
    void onPriceUpdate_setsInitialPrice() {
        // given
        Long userId = 1L;
        String symbol = "BTCUSDT";
        Queue q = makeQueue(1L, userId, symbol, Side.LONG);
        given(queueRepository.findByUserIdAndSymbolAndUseYnAndDelYnOrderBySortOrderAsc(
                userId, symbol, "Y", "N"))
                .willReturn(List.of(q));
        autoTradeService.syncSession(userId, symbol);

        // when
        autoTradeService.onPriceUpdate("BTCUSDT", "50000.00");

        // then
        assertThat(autoTradeService.getSession(userId, symbol).getPreviousPrice())
                .isEqualTo("50000.00");
        then(tradeService).should(never()).placeOrder(any(), any());
    }

    @Test
    @DisplayName("onPriceUpdate: 가격 상승 시 LONG 주문을 실행한다")
    void onPriceUpdate_executesLongOrderOnPriceIncrease() {
        // given
        Long userId = 1L;
        String symbol = "BTCUSDT";
        Queue q = makeQueue(1L, userId, symbol, Side.LONG);
        given(queueRepository.findByUserIdAndSymbolAndUseYnAndDelYnOrderBySortOrderAsc(
                userId, symbol, "Y", "N"))
                .willReturn(List.of(q));
        autoTradeService.syncSession(userId, symbol);
        given(tradeHistoryRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        // 1st update: 초기 가격 설정
        autoTradeService.onPriceUpdate("BTCUSDT", "50000.00");

        // when - 2nd update: 가격 상승 → LONG 신호
        autoTradeService.onPriceUpdate("BTCUSDT", "50100.00");

        // then
        then(tradeService).should(times(1)).placeOrder(any(), eq(userId));
    }

    @Test
    @DisplayName("onPriceUpdate: 다른 심볼의 세션에는 영향을 주지 않는다")
    void onPriceUpdate_doesNotAffectOtherSymbolSessions() {
        // given
        Long userId = 1L;
        Queue btcQueue = makeQueue(1L, userId, "BTCUSDT", Side.LONG);
        given(queueRepository.findByUserIdAndSymbolAndUseYnAndDelYnOrderBySortOrderAsc(
                userId, "BTCUSDT", "Y", "N"))
                .willReturn(List.of(btcQueue));
        autoTradeService.syncSession(userId, "BTCUSDT");

        // when - ETHUSDT 가격 변동 (BTCUSDT 세션에 영향 없어야 함)
        autoTradeService.onPriceUpdate("ETHUSDT", "3000.00");

        // then - BTCUSDT 세션의 previousPrice는 여전히 null
        assertThat(autoTradeService.getSession(userId, "BTCUSDT").getPreviousPrice()).isNull();
    }

    // ─────────────────────────────────────────────
    // 헬퍼 메서드
    // ─────────────────────────────────────────────

    private Queue makeQueue(Long id, Long userId, String symbol) {
        return makeQueue(id, userId, symbol, Side.LONG);
    }

    private Queue makeQueue(Long id, Long userId, String symbol, Side side) {
        return makeQueue(id, userId, symbol, side, 1);
    }

    private Queue makeQueue(Long id, Long userId, String symbol, Side side, int stepCount) {
        Queue q = new Queue();
        q.setId(id);
        q.setUserId(userId);
        q.setSymbol(symbol);
        q.setSortOrder(0);
        q.setUseYn("Y");
        q.setDelYn("N");
        q.setCreatedAt(LocalDateTime.now());

        List<QueueStep> steps = new ArrayList<>();
        for (int i = 0; i < stepCount; i++) {
            QueueStep step = new QueueStep();
            step.setId(id * 10 + i);
            step.setQueue(q);
            step.setStepOrder(i);
            step.setSide(side);
            step.setQuantity(new BigDecimal("0.01"));
            steps.add(step);
        }

        q.setSteps(steps);
        return q;
    }

    private FindTickerResponse makeTickerResponse(String symbol, String price) {
        FindTickerResponse.TickerInfo ticker = new FindTickerResponse.TickerInfo();
        ticker.setSymbol(symbol);
        ticker.setLastPrice(price);
        ticker.setVolume24h("10000.0");

        FindTickerResponse.Result result = new FindTickerResponse.Result();
        result.setCategory("linear");
        result.setList(new ArrayList<>(List.of(ticker)));

        FindTickerResponse response = new FindTickerResponse();
        response.setResult(result);
        return response;
    }
}
