package com.coin.webcointrader.common.autotrade.service;

import com.coin.webcointrader.common.autotrade.entity.Pattern;
import com.coin.webcointrader.common.autotrade.entity.PatternQueue;
import com.coin.webcointrader.common.enums.Side;
import com.coin.webcointrader.common.enums.OrderCancelResult;
import com.coin.webcointrader.common.autotrade.util.TradeCalculator;
import com.coin.webcointrader.common.autotrade.dto.AutoTradeSessionDTO;
import com.coin.webcointrader.common.autotrade.dto.AutoTradeAlertResponse;
import com.coin.webcointrader.common.autotrade.dto.AutoTradeStatusResponse;
import com.coin.webcointrader.common.autotrade.dto.QueueStateDTO;
import com.coin.webcointrader.common.autotrade.dto.TradePhase;
import com.coin.webcointrader.common.autotrade.repository.PatternQueueRepository;
import com.coin.webcointrader.common.trade.dto.InvestmentHistoryDTO;
import com.coin.webcointrader.common.trade.dto.TradeHistoryDTO;
import com.coin.webcointrader.common.trade.dto.ClosedPnlQueryDTO;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.enums.TradeMode;
import com.coin.webcointrader.common.enums.OrderSide;
import com.coin.webcointrader.common.trade.client.ExchangeTradeClient;
import com.coin.webcointrader.common.trade.dto.ExecutionDTO;
import com.coin.webcointrader.common.trade.dto.OrderRequestDTO;
import com.coin.webcointrader.common.trade.dto.OrderResultDTO;
import com.coin.webcointrader.common.ExchangeClientRegistry;
import com.coin.webcointrader.common.market.client.ExchangeMarketClient;
import com.coin.webcointrader.common.market.dto.KlineDTO;
import com.coin.webcointrader.common.market.dto.TickerDTO;
import com.coin.webcointrader.common.market.service.MarketService;
import com.coin.webcointrader.common.trade.service.TradeFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.coin.webcointrader.common.enums.PositionCloseType;
import com.coin.webcointrader.common.enums.TradeOrderType;
import com.coin.webcointrader.common.enums.OrderResult;
import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.trade.dto.PositionCloseDTO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;

import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AutoTradeServiceTest {

    @InjectMocks
    private AutoTradeService autoTradeService;

    @Mock
    private PatternQueueRepository patternQueueRepository;

    @Mock
    private TradeFacade tradeFacade;

    @Mock
    private MarketService marketService;

    @Mock
    private ExchangeClientRegistry adapterRegistry;

    @Mock
    private ExchangeTradeClient bybitTrade;

    @Mock
    private ExchangeMarketClient bybitMarket;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private ExecutorService closeRecordExecutor;

    @Mock
    private ExecutorService sessionLaneExecutor;

    @Mock
    private VolumeMonitorService volumeMonitor;

    // 가짜 거래소 포지션 수량 (setUp의 placeOrder 기본 응답이 갱신)
    private final java.util.concurrent.atomic.AtomicReference<BigDecimal> fakePositionSize =
            new java.util.concurrent.atomic.AtomicReference<>(BigDecimal.ZERO);

    @BeforeEach
    void setUpAdapterRegistry() {
        // 모든 테스트에서 adapterRegistry 기본 동작 설정 (lenient: 일부 테스트에서 미사용 가능)
        given(adapterRegistry.trades()).willReturn(List.of(bybitTrade));
        given(bybitTrade.getExchangeType()).willReturn(ExchangeType.BYBIT);
        // 추정 수수료 계산용 거래소 taker 수수료율 (기본: Bybit 값)
        given(adapterRegistry.trade(ExchangeType.BYBIT)).willReturn(bybitTrade);
        given(bybitTrade.getTakerFeeRate()).willReturn(new BigDecimal("0.00055"));
        given(bybitTrade.getMakerFeeRate()).willReturn(new BigDecimal("0.0002"));
        given(adapterRegistry.markets()).willReturn(List.of(bybitMarket));
        given(bybitMarket.getExchangeType()).willReturn(ExchangeType.BYBIT);
        // 같은 타입 Mock이 2개라 생성자 주입이 모호하므로 필드에 명시적으로 지정
        ReflectionTestUtils.setField(autoTradeService, "closeRecordExecutor", closeRecordExecutor);
        ReflectionTestUtils.setField(autoTradeService, "sessionLaneExecutor", sessionLaneExecutor);
        // 청산 후처리 실행기는 기본적으로 동기 실행 (기존 이력 저장 검증 유지)
        willAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).given(closeRecordExecutor).execute(any(Runnable.class));
        // 세션 레인 실행기도 기본적으로 동기 실행 (기존 가격 처리 검증 유지)
        willAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).given(sessionLaneExecutor).execute(any(Runnable.class));
        // 가짜 거래소 포지션: 진입 주문은 그 수량만큼 보유, 청산(reduceOnly) 주문은 0 (MAIN만, SIM은 실제처럼 null)
        fakePositionSize.set(BigDecimal.ZERO);
        willAnswer(inv -> {
            OrderRequestDTO req = inv.getArgument(0);
            if (req == null) return null; // 다른 테스트의 given(placeOrder(any()...)) 스텁 등록 호출
            if (req.getPrice() != null) return null; // 지정가(익절 대기 주문)는 체결 전이므로 포지션 변화 없음
            fakePositionSize.set(req.isReduceOnly() ? BigDecimal.ZERO : new BigDecimal(req.getQty()));
            return null;
        }).given(tradeFacade).placeOrder(any(), any(), any(), any(), any());
        given(tradeFacade.getPositionSize(any(), any(), any(), eq(TradeMode.MAIN)))
                .willAnswer(inv -> fakePositionSize.get());
        // 익절 지정가 tick 반올림용 기본 tickSize
        given(marketService.getTickSize(any(), any())).willReturn("0.10");
    }

    // ─────────────────────────────────────────────
    // init (가격 리스너 등록)
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("init: 시작 시 시세 클라이언트에 가격/봉마감 리스너를 등록한다")
    void init_registersPriceListener() {
        autoTradeService.init();
        then(bybitMarket).should(times(1)).addTickerListener(any());
        then(bybitMarket).should(times(1)).addKlineListener(any());
    }

    @Test
    @DisplayName("init: private 스트림 지원 어댑터에 포지션 청산 리스너를 등록한다")
    void init_registersPositionCloseListener() {
        autoTradeService.init();
        then(bybitTrade).should(times(1)).addPositionCloseListener(any());
    }

    // ─────────────────────────────────────────────
    // syncSession
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("syncSession: 활성 큐가 있으면 새 세션을 생성한다")
    void syncSession_createsNewSession() {
        Long userId = 1L;
        String symbol = "BTCUSDT";
        PatternQueue q = makePatternQueue(1L, userId, symbol, Side.LONG);
        given(patternQueueRepository.findByUserIdAndSymbolAndIsActiveAndTradeModeAndExchangeTypeOrderByCreatedAtAsc(
                userId, symbol, true, TradeMode.MAIN, ExchangeType.BYBIT)).willReturn(List.of(q));

        autoTradeService.syncSession(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN);

        assertThat(autoTradeService.isActive(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN)).isTrue();
        AutoTradeSessionDTO session = autoTradeService.getSession(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN);
        assertThat(session).isNotNull();
        // 큐 상태가 초기화되어야 함
        assertThat(session.getQueueStates()).containsKey(q.getId());
        assertThat(session.getQueueStates().get(q.getId()).getPhase()).isEqualTo(TradePhase.TRIGGER_WAIT);
    }

    @Test
    @DisplayName("syncSession: 기존 세션이 있고 활성 큐가 있으면 세션을 갱신한다")
    void syncSession_updatesExistingSession() {
        Long userId = 1L;
        String symbol = "BTCUSDT";
        PatternQueue q1 = makePatternQueue(1L, userId, symbol, Side.LONG);
        given(patternQueueRepository.findByUserIdAndSymbolAndIsActiveAndTradeModeAndExchangeTypeOrderByCreatedAtAsc(
                userId, symbol, true, TradeMode.MAIN, ExchangeType.BYBIT)).willReturn(List.of(q1));

        autoTradeService.syncSession(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN);

        PatternQueue q2 = makePatternQueue(2L, userId, symbol, Side.SHORT);
        given(patternQueueRepository.findByUserIdAndSymbolAndIsActiveAndTradeModeAndExchangeTypeOrderByCreatedAtAsc(
                userId, symbol, true, TradeMode.MAIN, ExchangeType.BYBIT)).willReturn(List.of(q1, q2));

        autoTradeService.syncSession(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN);

        assertThat(autoTradeService.getSession(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN).getQueues()).hasSize(2);
        assertThat(autoTradeService.getSession(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN).getQueueStates()).hasSize(2);
    }

    @Test
    @DisplayName("syncSession: 활성 큐가 없으면 세션을 제거한다")
    void syncSession_removesSessionWhenNoActiveQueues() {
        Long userId = 1L;
        String symbol = "BTCUSDT";
        PatternQueue q = makePatternQueue(1L, userId, symbol, Side.LONG);

        given(patternQueueRepository.findByUserIdAndSymbolAndIsActiveAndTradeModeAndExchangeTypeOrderByCreatedAtAsc(
                userId, symbol, true, TradeMode.MAIN, ExchangeType.BYBIT)).willReturn(List.of(q));
        autoTradeService.syncSession(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN);

        given(patternQueueRepository.findByUserIdAndSymbolAndIsActiveAndTradeModeAndExchangeTypeOrderByCreatedAtAsc(
                userId, symbol, true, TradeMode.MAIN, ExchangeType.BYBIT)).willReturn(List.of());

        autoTradeService.syncSession(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN);
        assertThat(autoTradeService.isActive(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN)).isFalse();
    }

    @Test
    @DisplayName("syncSession: WebSocket 구독을 동기화한다")
    void syncSession_syncsWebSocketSubscriptions() {
        Long userId = 1L;
        String symbol = "BTCUSDT";
        PatternQueue q = makePatternQueue(1L, userId, symbol, Side.LONG);
        given(patternQueueRepository.findByUserIdAndSymbolAndIsActiveAndTradeModeAndExchangeTypeOrderByCreatedAtAsc(
                userId, symbol, true, TradeMode.MAIN, ExchangeType.BYBIT)).willReturn(List.of(q));

        autoTradeService.syncSession(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN);

        ArgumentCaptor<Set<String>> captor = ArgumentCaptor.forClass(Set.class);
        then(bybitMarket).should(atLeastOnce()).syncSubscriptions(captor.capture());
        assertThat(captor.getValue()).contains("BTCUSDT");
    }

    @Test
    @DisplayName("syncSession: MAIN 세션이 시작되면 해당 사용자로 private 스트림을 동기화한다")
    void syncSession_main_syncsPrivateStreamWithUser() {
        Long userId = 1L;
        String symbol = "BTCUSDT";
        PatternQueue q = makePatternQueue(1L, userId, symbol, Side.LONG);
        given(patternQueueRepository.findByUserIdAndSymbolAndIsActiveAndTradeModeAndExchangeTypeOrderByCreatedAtAsc(
                userId, symbol, true, TradeMode.MAIN, ExchangeType.BYBIT)).willReturn(List.of(q));

        autoTradeService.syncSession(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN);

        then(bybitTrade).should().syncPrivateStreams(Set.of(userId));
    }

    @Test
    @DisplayName("syncSession: SIM 세션만 있으면 private 스트림을 빈 집합으로 동기화한다 (연결하지 않음)")
    void syncSession_simOnly_syncsPrivateStreamWithEmptySet() {
        Long userId = 1L;
        String symbol = "BTCUSDT";
        PatternQueue q = makePatternQueue(1L, userId, symbol, Side.LONG);
        given(patternQueueRepository.findByUserIdAndSymbolAndIsActiveAndTradeModeAndExchangeTypeOrderByCreatedAtAsc(
                userId, symbol, true, TradeMode.SIM, ExchangeType.BYBIT)).willReturn(List.of(q));

        autoTradeService.syncSession(userId, ExchangeType.BYBIT, symbol, TradeMode.SIM);

        assertThat(autoTradeService.isActive(userId, ExchangeType.BYBIT, symbol, TradeMode.SIM)).isTrue();
        then(bybitTrade).should().syncPrivateStreams(Set.of());
    }

    @Test
    @DisplayName("syncSession: 마지막 MAIN 세션이 종료되면 private 스트림을 빈 집합으로 동기화한다")
    void syncSession_lastMainRemoved_syncsPrivateStreamWithEmptySet() {
        Long userId = 1L;
        String symbol = "BTCUSDT";
        PatternQueue q = makePatternQueue(1L, userId, symbol, Side.LONG);
        given(patternQueueRepository.findByUserIdAndSymbolAndIsActiveAndTradeModeAndExchangeTypeOrderByCreatedAtAsc(
                userId, symbol, true, TradeMode.MAIN, ExchangeType.BYBIT)).willReturn(List.of(q));
        autoTradeService.syncSession(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN);

        given(patternQueueRepository.findByUserIdAndSymbolAndIsActiveAndTradeModeAndExchangeTypeOrderByCreatedAtAsc(
                userId, symbol, true, TradeMode.MAIN, ExchangeType.BYBIT)).willReturn(List.of());
        autoTradeService.syncSession(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN);

        ArgumentCaptor<Set<Long>> captor = ArgumentCaptor.forClass(Set.class);
        then(bybitTrade).should(times(2)).syncPrivateStreams(captor.capture());
        assertThat(captor.getAllValues()).containsExactly(Set.of(userId), Set.of());
    }

    // ─────────────────────────────────────────────
    // isActive / getSession
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("isActive: 세션이 없으면 false를 반환한다")
    void isActive_returnsFalseWhenNoSession() {
        assertThat(autoTradeService.isActive(1L, ExchangeType.BYBIT, "BTCUSDT", TradeMode.MAIN)).isFalse();
    }

    @Test
    @DisplayName("getSession: 세션이 없으면 null을 반환한다")
    void getSession_returnsNullWhenNotFound() {
        assertThat(autoTradeService.getSession(1L, ExchangeType.BYBIT, "BTCUSDT", TradeMode.MAIN)).isNull();
    }

    // ─────────────────────────────────────────────
    // tick (fallback 스케줄러)
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("tick: 활성 세션이 없으면 marketService를 호출하지 않는다")
    void tick_doesNothingWhenNoSessions() {
        autoTradeService.tick();
        then(marketService).should(never()).getTickers(any());
    }

    @Test
    @DisplayName("tick: WebSocket이 활성 상태이면 REST 폴링을 건너뛴다")
    void tick_skipsWhenWebSocketIsActive() {
        Long userId = 1L;
        String symbol = "BTCUSDT";
        PatternQueue q = makePatternQueue(1L, userId, symbol, Side.LONG);
        given(patternQueueRepository.findByUserIdAndSymbolAndIsActiveAndTradeModeAndExchangeTypeOrderByCreatedAtAsc(
                userId, symbol, true, TradeMode.MAIN, ExchangeType.BYBIT)).willReturn(List.of(q));
        autoTradeService.syncSession(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN);
        given(marketService.isWsActive(ExchangeType.BYBIT)).willReturn(true);

        autoTradeService.tick();

        then(marketService).should(never()).getTickers(any());
    }

    @Test
    @DisplayName("tick: getTickers 응답이 비어 있으면 처리를 중단한다")
    void tick_stopsWhenTickersNull() {
        Long userId = 1L;
        String symbol = "BTCUSDT";
        PatternQueue q = makePatternQueue(1L, userId, symbol, Side.LONG);
        given(patternQueueRepository.findByUserIdAndSymbolAndIsActiveAndTradeModeAndExchangeTypeOrderByCreatedAtAsc(
                userId, symbol, true, TradeMode.MAIN, ExchangeType.BYBIT)).willReturn(List.of(q));
        autoTradeService.syncSession(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN);
        given(marketService.getTickers(ExchangeType.BYBIT)).willReturn(List.of());

        assertThatCode(() -> autoTradeService.tick()).doesNotThrowAnyException();
        then(tradeFacade).should(never()).placeOrder(any(), any(), any(), any(), any());
    }

    // ─────────────────────────────────────────────
    // onPriceUpdate (WebSocket 이벤트 핸들러)
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("onPriceUpdate: 활성 세션이 없으면 아무 작업도 하지 않는다")
    void onPriceUpdate_doesNothingWhenNoSessions() {
        assertThatCode(() -> autoTradeService.onPriceUpdate(ExchangeType.BYBIT, "BTCUSDT", "50000.00"))
                .doesNotThrowAnyException();
    }

    // ─────────────────────────────────────────────
    // 세션 레인 (WS 스레드 → 세션별 처리 스레드)
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("세션 레인")
    class SessionLaneTest {

        @SuppressWarnings("unchecked")
        private ConcurrentHashMap<String, AutoTradeSessionDTO> activeSessions() {
            return (ConcurrentHashMap<String, AutoTradeSessionDTO>)
                    ReflectionTestUtils.getField(autoTradeService, "activeSessions");
        }

        @Test
        @DisplayName("onPriceUpdate는 레인에 작업만 제출하고 호출 스레드에서 처리하지 않는다")
        void onPriceUpdate_submitsToLaneOnly() {
            // given - 레인 실행기가 작업을 보관만 하도록 설정
            willDoNothing().given(sessionLaneExecutor).execute(any(Runnable.class));
            PatternQueue queue = makePatternQueue(1L, 1L, "BTCUSDT", Side.LONG);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            activeSessions().put("1:BTCUSDT", session);

            // when
            autoTradeService.onPriceUpdate(ExchangeType.BYBIT, "BTCUSDT", "100");

            // then - 제출 전에는 기준가 미설정
            QueueStateDTO state = session.getQueueStates().get(1L);
            assertThat(state.getBasePrice()).isNull();
            ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
            then(sessionLaneExecutor).should(times(1)).execute(task.capture());

            // 레인 작업 실행 후 기준가 설정
            task.getValue().run();
            assertThat(state.getBasePrice()).isEqualTo("100");
        }

        @Test
        @DisplayName("처리 전 여러 가격이 들어오면 작업은 1번만 제출되고 최신 가격만 처리한다")
        void onPriceUpdate_coalescesToLatestPrice() {
            // given
            willDoNothing().given(sessionLaneExecutor).execute(any(Runnable.class));
            PatternQueue queue = makePatternQueue(1L, 1L, "BTCUSDT", Side.LONG);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            activeSessions().put("1:BTCUSDT", session);

            // when - 레인 실행 전 가격 3회 수신
            autoTradeService.onPriceUpdate(ExchangeType.BYBIT, "BTCUSDT", "100");
            autoTradeService.onPriceUpdate(ExchangeType.BYBIT, "BTCUSDT", "101");
            autoTradeService.onPriceUpdate(ExchangeType.BYBIT, "BTCUSDT", "102");

            // then - 제출 1회, 실행 시 마지막 가격만 기준가로 사용
            ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
            then(sessionLaneExecutor).should(times(1)).execute(task.capture());
            task.getValue().run();
            assertThat(session.getQueueStates().get(1L).getBasePrice()).isEqualTo("102");
        }

        @Test
        @DisplayName("한 세션이 처리 중 막혀도 다른 세션은 처리된다")
        void blockedSession_doesNotBlockOtherSession() throws Exception {
            // given - 실제 2스레드 풀 사용
            ExecutorService pool = Executors.newFixedThreadPool(2);
            ReflectionTestUtils.setField(autoTradeService, "sessionLaneExecutor", pool);
            CountDownLatch btcEntered = new CountDownLatch(1); // BTC 레인이 push 단계에 진입
            CountDownLatch btcRelease = new CountDownLatch(1); // BTC 레인 해제 신호
            CountDownLatch ethPushed = new CountDownLatch(1);  // ETH 레인 처리 완료

            // BTC 상태 push는 해제 신호까지 대기, ETH 상태 push는 완료 신호
            willAnswer(inv -> {
                btcEntered.countDown();
                btcRelease.await(5, TimeUnit.SECONDS);
                return null;
            }).given(messagingTemplate).convertAndSendToUser(
                    eq("1"), eq("/queue/autotrade.status.BYBIT.MAIN.BTCUSDT"), any(AutoTradeStatusResponse.class));
            willAnswer(inv -> {
                ethPushed.countDown();
                return null;
            }).given(messagingTemplate).convertAndSendToUser(
                    eq("2"), eq("/queue/autotrade.status.BYBIT.MAIN.ETHUSDT"), any(AutoTradeStatusResponse.class));

            AutoTradeSessionDTO btc = makeSession(1L, "BTCUSDT", makePatternQueue(1L, 1L, "BTCUSDT", Side.LONG));
            AutoTradeSessionDTO eth = makeSession(2L, "ETHUSDT", makePatternQueue(2L, 2L, "ETHUSDT", Side.LONG));
            activeSessions().put("1:BTCUSDT", btc);
            activeSessions().put("2:ETHUSDT", eth);

            try {
                // when - BTC 레인이 막힌 상태에서 ETH 가격 수신
                autoTradeService.onPriceUpdate(ExchangeType.BYBIT, "BTCUSDT", "100");
                assertThat(btcEntered.await(2, TimeUnit.SECONDS)).isTrue();
                autoTradeService.onPriceUpdate(ExchangeType.BYBIT, "ETHUSDT", "200");

                // then - ETH는 BTC 해제를 기다리지 않고 처리됨
                assertThat(ethPushed.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(eth.getQueueStates().get(2L).getBasePrice()).isEqualTo("200");
            } finally {
                btcRelease.countDown();
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("같은 세션의 레인 작업은 동시에 하나만 실행된다 (큐 상태 단일 스레드 접근 보장)")
        void sameSessionLane_runsOneAtATime() throws Exception {
            // given - 실제 2스레드 풀 사용 (스레드 여유가 있어도 같은 세션은 병렬 실행되면 안 됨)
            ExecutorService pool = Executors.newFixedThreadPool(2);
            ReflectionTestUtils.setField(autoTradeService, "sessionLaneExecutor", pool);
            java.util.concurrent.atomic.AtomicInteger running = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger maxRunning = new java.util.concurrent.atomic.AtomicInteger();
            CountDownLatch firstEntered = new CountDownLatch(1); // 첫 처리가 push 단계에 진입
            CountDownLatch release = new CountDownLatch(1);      // 첫 처리 해제 신호
            CountDownLatch pushedTwice = new CountDownLatch(2);  // 첫 가격 + 밀린 최신 가격 처리 완료

            // push 시 동시 실행 수 기록, 첫 push는 해제 신호까지 대기
            willAnswer(inv -> {
                maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
                firstEntered.countDown();
                release.await(5, TimeUnit.SECONDS);
                running.decrementAndGet();
                pushedTwice.countDown();
                return null;
            }).given(messagingTemplate).convertAndSendToUser(
                    eq("1"), eq("/queue/autotrade.status.BYBIT.MAIN.BTCUSDT"), any(AutoTradeStatusResponse.class));

            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", makePatternQueue(1L, 1L, "BTCUSDT", Side.LONG));
            activeSessions().put("1:BTCUSDT", session);

            try {
                // when - 첫 처리가 막힌 동안 가격 2개 추가 수신
                autoTradeService.onPriceUpdate(ExchangeType.BYBIT, "BTCUSDT", "100");
                assertThat(firstEntered.await(2, TimeUnit.SECONDS)).isTrue();
                autoTradeService.onPriceUpdate(ExchangeType.BYBIT, "BTCUSDT", "101");
                autoTradeService.onPriceUpdate(ExchangeType.BYBIT, "BTCUSDT", "102");
                release.countDown();

                // then - 동시 실행은 최대 1개, 밀린 가격은 같은 레인에서 이어서 처리
                assertThat(pushedTwice.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(maxRunning.get()).isEqualTo(1);
            } finally {
                release.countDown();
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("작업 제출 후 세션이 제거되면 처리하지 않는다")
        void removedSession_isNotProcessed() {
            // given
            willDoNothing().given(sessionLaneExecutor).execute(any(Runnable.class));
            PatternQueue queue = makePatternQueue(1L, 1L, "BTCUSDT", Side.LONG);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            activeSessions().put("1:BTCUSDT", session);
            autoTradeService.onPriceUpdate(ExchangeType.BYBIT, "BTCUSDT", "100");
            ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
            then(sessionLaneExecutor).should().execute(task.capture());

            // when - 레인 실행 전 세션 종료
            activeSessions().remove("1:BTCUSDT");
            task.getValue().run();

            // then - 처리·push 없음
            assertThat(session.getQueueStates().get(1L).getBasePrice()).isNull();
            then(messagingTemplate).should(never())
                    .convertAndSendToUser(anyString(), anyString(), any(AutoTradeStatusResponse.class));
        }

        @Test
        @DisplayName("tick: WS 비활성 시 REST 가격을 레인에 제출만 하고 스케줄러 스레드에서 처리하지 않는다")
        void tick_submitsToLaneOnly() {
            // given - WS 비활성 + REST 티커 응답
            willDoNothing().given(sessionLaneExecutor).execute(any(Runnable.class));
            PatternQueue queue = makePatternQueue(1L, 1L, "BTCUSDT", Side.LONG);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            activeSessions().put("1:BTCUSDT", session);
            given(marketService.isWsActive(ExchangeType.BYBIT)).willReturn(false);
            given(marketService.getTickers(ExchangeType.BYBIT)).willReturn(makeTickers("BTCUSDT", "300"));

            // when
            autoTradeService.tick();

            // then - 실행 전에는 기준가 미설정, 실행 후 REST 가격으로 설정
            QueueStateDTO state = session.getQueueStates().get(1L);
            assertThat(state.getBasePrice()).isNull();
            ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
            then(sessionLaneExecutor).should(times(1)).execute(task.capture());
            task.getValue().run();
            assertThat(state.getBasePrice()).isEqualTo("300");
        }

        @Test
        @ExtendWith(OutputCaptureExtension.class)
        @DisplayName("레인 작업이 3초 이상 대기 후 실행되면 경고 로그를 남긴다")
        void warnsWhenLaneWaitIsLong(CapturedOutput output) throws Exception {
            // given - 제출된 작업을 보관
            willDoNothing().given(sessionLaneExecutor).execute(any(Runnable.class));
            PatternQueue queue = makePatternQueue(1L, 1L, "BTCUSDT", Side.LONG);
            activeSessions().put("1:BTCUSDT", makeSession(1L, "BTCUSDT", queue));
            autoTradeService.onPriceUpdate(ExchangeType.BYBIT, "BTCUSDT", "100");
            ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
            then(sessionLaneExecutor).should().execute(task.capture());

            // when - 3초 넘게 대기한 뒤 실행 (풀 스레드가 모두 바쁜 상황 재현)
            Thread.sleep(3_100);
            task.getValue().run();

            // then
            assertThat(output.getOut()).contains("[세션 레인] 실행 대기 지연");
        }

        @Test
        @ExtendWith(OutputCaptureExtension.class)
        @DisplayName("레인 작업이 바로 실행되면 경고 로그를 남기지 않는다")
        void noWarnWhenLaneRunsImmediately(CapturedOutput output) {
            // given - 기본 설정(즉시 실행)
            PatternQueue queue = makePatternQueue(1L, 1L, "BTCUSDT", Side.LONG);
            activeSessions().put("1:BTCUSDT", makeSession(1L, "BTCUSDT", queue));

            // when
            autoTradeService.onPriceUpdate(ExchangeType.BYBIT, "BTCUSDT", "100");

            // then
            assertThat(output.getOut()).doesNotContain("[세션 레인] 실행 대기 지연");
        }
    }

    // ─────────────────────────────────────────────
    // 트리거 대기 (processTriggerWait)
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("processTriggerWait")
    class TriggerWaitTest {

        @Test
        @DisplayName("최초 호출 시 기준가격과 시간을 설정한다")
        void setsBasePriceOnFirstCall() {
            PatternQueue queue = makePatternQueue(1L, 1L, "BTCUSDT", Side.LONG);
            QueueStateDTO state = QueueStateDTO.initial(1L);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            autoTradeService.processTriggerWait(queue, state, session, "50000.00");

            assertThat(state.getBasePrice()).isEqualTo("50000.00");
            assertThat(state.getBaseTime()).isNotNull();
            assertThat(state.getPhase()).isEqualTo(TradePhase.TRIGGER_WAIT);
        }

        @Test
        @DisplayName("상승률 충족 시 LONG으로 전환하고 첫 블록 자동 매칭으로 currentBlockOrder=2부터 시작한다")
        void transitionsToLongOnRiseAboveTrigger() {
            PatternQueue queue = makePatternQueue(1L, 1L, "BTCUSDT", Side.LONG);
            QueueStateDTO state = QueueStateDTO.initial(1L);
            state.setBasePrice("50000.00");
            state.setBaseTime(LocalDateTime.now());
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            // 50000 → 50600 = +1.2% (triggerRate=1.0% 초과)
            autoTradeService.processTriggerWait(queue, state, session, "50600.00");

            assertThat(state.getDirection()).isEqualTo(Side.LONG);
            assertThat(state.getPhase()).isEqualTo(TradePhase.BLOCK_MATCHING);
            // 첫 블록은 트리거로 자동 매칭된 것으로 간주 → currentBlockOrder=2부터 시작
            assertThat(state.getCurrentBlockOrder()).isEqualTo(2);
        }

        @Test
        @DisplayName("하락률 충족 시 SHORT으로 전환하고 첫 블록 자동 매칭으로 currentBlockOrder=2부터 시작한다")
        void transitionsToShortOnDropBelowTrigger() {
            // SHORT 패턴도 포함된 큐 생성
            PatternQueue queue = makePatternQueueWithBothPatterns(1L, 1L, "BTCUSDT");
            QueueStateDTO state = QueueStateDTO.initial(1L);
            state.setBasePrice("50000.00");
            state.setBaseTime(LocalDateTime.now());
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            // 50000 → 49400 = -1.2% (triggerRate=1.0% 초과)
            autoTradeService.processTriggerWait(queue, state, session, "49400.00");

            assertThat(state.getDirection()).isEqualTo(Side.SHORT);
            assertThat(state.getPhase()).isEqualTo(TradePhase.BLOCK_MATCHING);
            assertThat(state.getCurrentBlockOrder()).isEqualTo(2);
        }
    }

    // ─────────────────────────────────────────────
    // 패턴 선택 (selectPattern)
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("selectPattern")
    class SelectPatternTest {

        @Test
        @DisplayName("LONG 방향이면 첫 블록이 LONG인 패턴을 선택한다")
        void selectsLongPattern() {
            List<Pattern> patterns = makeBothPatterns(1);

            Pattern result = autoTradeService.selectPattern(patterns, Side.LONG);

            assertThat(result).isNotNull();
            // 첫 번째 조건 블록이 LONG인 패턴
            assertThat(result.getBlocks().get(0)).isEqualTo(Side.LONG);
        }

        @Test
        @DisplayName("SHORT 방향이면 첫 블록이 SHORT인 패턴을 선택한다")
        void selectsShortPattern() {
            List<Pattern> patterns = makeBothPatterns(1);

            Pattern result = autoTradeService.selectPattern(patterns, Side.SHORT);

            assertThat(result).isNotNull();
            assertThat(result.getBlocks().get(0)).isEqualTo(Side.SHORT);
        }
    }

    // ─────────────────────────────────────────────
    // 봉 마감 push 처리 (onKlineConfirmed)
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("onKlineConfirmed")
    class OnKlineConfirmedTest {

        /**
         * 활성 세션에 큐를 등록하고 BLOCK_MATCHING 상태로 만든다.
         * blockBaseTime은 mock 봉 구간([startMs, endMs])에 속하도록 설정.
         */
        private AutoTradeSessionDTO setupSessionWithCondBlock(PatternQueue queue, long blockBaseMs) {
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            QueueStateDTO state = session.getQueueStates().get(queue.getId());
            state.setPhase(TradePhase.BLOCK_MATCHING);
            state.setDirection(Side.LONG);
            state.setActivePatternId(10L);
            state.setCurrentBlockOrder(1); // 조건 블록 위치
            state.setBlockBaseTime(LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(blockBaseMs),
                    java.time.ZoneId.systemDefault()));
            state.setBlockBasePrice("50000.00");

            // activeSessions에 세션 직접 등록 (init 호출 없이도 onKlineConfirmed가 작동)
            var activeSessions = (java.util.concurrent.ConcurrentHashMap<String, AutoTradeSessionDTO>)
                    ReflectionTestUtils.getField(autoTradeService, "activeSessions");
            session.setTradeMode(TradeMode.MAIN);
            activeSessions.put(1L + ":BTCUSDT:" + TradeMode.MAIN, session);
            return session;
        }

        private KlineDTO makeKline(long startMs, String open, String close) {
            return KlineDTO.builder()
                    .symbol("BTCUSDT")
                    .startTime(startMs)
                    .endTime(startMs + 59_999) // 1분봉의 마지막 ms
                    .open(open)
                    .close(close)
                    .confirmed(true)
                    .build();
        }

        @Test
        @DisplayName("onKlineConfirmed는 레인에 작업만 제출하고 호출 스레드에서 처리하지 않는다")
        void submitsToLaneOnly() {
            // given - 레인 실행기가 작업을 보관만 하도록 설정
            willDoNothing().given(sessionLaneExecutor).execute(any(Runnable.class));
            PatternQueue queue = makeThreeBlockLongQueue(10);
            long startMs = System.currentTimeMillis() - 60_000;
            AutoTradeSessionDTO session = setupSessionWithCondBlock(queue, startMs + 5_000);
            QueueStateDTO state = session.getQueueStates().get(queue.getId());

            // when - 양봉 수신
            autoTradeService.onKlineConfirmed(ExchangeType.BYBIT, makeKline(startMs, "50000", "55000"));

            // then - 레인 실행 전에는 블록 이동 없음
            assertThat(state.getCurrentBlockOrder()).isEqualTo(1);
            ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
            then(sessionLaneExecutor).should(times(1)).execute(task.capture());

            // 레인 실행 후 다음 블록으로 이동
            task.getValue().run();
            assertThat(state.getCurrentBlockOrder()).isEqualTo(2);
        }

        /** epoch ms → 시스템 타임존 LocalDateTime */
        private LocalDateTime toLocalDateTime(long epochMs) {
            return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMs), java.time.ZoneId.systemDefault());
        }

        @Test
        @DisplayName("이미 마감된 연속 봉 2개가 쌓여도 유실 없이 순서대로 모두 처리한다 (레인 지연)")
        void processesAllPendingKlinesInOrder() {
            // given - 3블록 패턴 cond(L), cond(L), leaf(L), 두 봉 모두 이미 끝난 과거 봉 (레인이 1분 넘게 밀린 상황)
            willDoNothing().given(sessionLaneExecutor).execute(any(Runnable.class));
            PatternQueue queue = makeThreeBlockLongQueue(10);
            long firstStartMs = System.currentTimeMillis() - 180_000;
            AutoTradeSessionDTO session = setupSessionWithCondBlock(queue, firstStartMs + 5_000);
            QueueStateDTO state = session.getQueueStates().get(queue.getId());

            // when - 첫 봉 + 바로 다음 봉 (실제 거래소 봉 순서)
            autoTradeService.onKlineConfirmed(ExchangeType.BYBIT, makeKline(firstStartMs, "50000", "55000"));
            autoTradeService.onKlineConfirmed(ExchangeType.BYBIT, makeKline(firstStartMs + 60_000, "55000", "56000"));

            // then - 작업 제출 1회, 실행 시 두 봉 모두 반영 (블록 1 → 2 → 3)
            ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
            then(sessionLaneExecutor).should(times(1)).execute(task.capture());
            task.getValue().run();
            assertThat(state.getCurrentBlockOrder()).isEqualTo(3);
        }

        @Test
        @DisplayName("레인 실행 전 세션이 제거되면 봉 마감을 적용하지 않고 재제출도 하지 않는다")
        void removedSession_discardsPendingKlines() {
            // given
            willDoNothing().given(sessionLaneExecutor).execute(any(Runnable.class));
            PatternQueue queue = makeThreeBlockLongQueue(10);
            long startMs = System.currentTimeMillis() - 60_000;
            AutoTradeSessionDTO session = setupSessionWithCondBlock(queue, startMs + 5_000);
            autoTradeService.onKlineConfirmed(ExchangeType.BYBIT, makeKline(startMs, "50000", "55000"));
            ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
            then(sessionLaneExecutor).should().execute(task.capture());

            // when - 레인 실행 전 세션 종료
            var activeSessions = (java.util.concurrent.ConcurrentHashMap<String, AutoTradeSessionDTO>)
                    ReflectionTestUtils.getField(autoTradeService, "activeSessions");
            activeSessions.clear();
            task.getValue().run();

            // then - 블록 이동 없음, 남은 봉 마감으로 인한 재제출 없음
            assertThat(session.getQueueStates().get(queue.getId()).getCurrentBlockOrder()).isEqualTo(1);
            then(sessionLaneExecutor).should(times(1)).execute(any(Runnable.class));
        }

        @Test
        @DisplayName("양봉(close > open)이고 조건 블록이 LONG이면 다음 블록으로 이동한다")
        void advancesOnLongCandleMatchingLongBlock() {
            // 3블록 패턴: cond(L), cond(L), leaf(L) — currentBlockOrder=1(첫 cond)에서 시작
            PatternQueue queue = makeThreeBlockLongQueue(10);
            // 봉 구간을 과거로 잡아 blockBaseTime이 안전하게 origBaseTime > 이후 갱신값 비교 가능
            long startMs = System.currentTimeMillis() - 60_000;
            AutoTradeSessionDTO session = setupSessionWithCondBlock(queue, startMs + 5_000);

            // 양봉 (50000 → 55000)
            autoTradeService.onKlineConfirmed(ExchangeType.BYBIT, makeKline(startMs, "50000", "55000"));

            QueueStateDTO state = session.getQueueStates().get(queue.getId());
            assertThat(state.getCurrentBlockOrder()).isEqualTo(2);
            assertThat(state.getPhase()).isEqualTo(TradePhase.BLOCK_MATCHING);
        }

        @Test
        @DisplayName("음봉(close < open)이면 같은 단계의 SHORT 시작 패턴으로 전환한다 (단계 유지)")
        void switchesToShortPatternInSameStep() {
            // 1단계에 LONG 패턴(id=100) + SHORT 패턴(id=200) 양쪽
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            long startMs = System.currentTimeMillis() - 60_000;
            AutoTradeSessionDTO session = setupSessionWithCondBlock(queue, startMs + 5_000);
            QueueStateDTO state = session.getQueueStates().get(queue.getId());
            state.setActivePatternId(100L); // LONG 패턴 매칭 중

            // 음봉 (50000 → 45000) → SHORT 신호 → block1(LONG)과 반대
            autoTradeService.onKlineConfirmed(ExchangeType.BYBIT, makeKline(startMs, "50000", "45000"));

            // 같은 1단계 유지, SHORT 시작 패턴(id=200)으로 전환
            assertThat(state.getCurrentStepLevel()).isEqualTo(1);
            assertThat(state.getActivePatternId()).isEqualTo(200L);
            assertThat(state.getDirection()).isEqualTo(Side.SHORT);
            assertThat(state.getCurrentBlockOrder()).isEqualTo(2); // 첫 블록 자동 매칭으로 2부터
            assertThat(state.getPhase()).isEqualTo(TradePhase.BLOCK_MATCHING);
            then(tradeFacade).should(never()).placeOrder(any(), any(), any(), any(), any());
            then(tradeFacade).should(never()).saveInvestmentHistory(any(), any());
        }

        @Test
        @DisplayName("도지(close == open)이면 블록 위치 변화 없이 다음 봉을 재대기한다")
        void resetsAndWaitsOnDoji() {
            PatternQueue queue = makeThreeBlockLongQueue(10);
            long startMs = System.currentTimeMillis() - 60_000;
            AutoTradeSessionDTO session = setupSessionWithCondBlock(queue, startMs + 5_000);
            QueueStateDTO state = session.getQueueStates().get(queue.getId());
            LocalDateTime origBaseTime = state.getBlockBaseTime();

            // 도지 (open == close)
            autoTradeService.onKlineConfirmed(ExchangeType.BYBIT, makeKline(startMs, "50000", "50000"));

            assertThat(state.getCurrentBlockOrder()).isEqualTo(1); // 변화 없음
            assertThat(state.getBlockBaseTime()).isAfter(origBaseTime); // 기준 시각 리셋
        }

        @Test
        @DisplayName("신호 일치 시 기준 시각을 다음 봉 시작(end+1)으로 맞춘다 (서버 시계 무관)")
        void matchSetsBlockBaseToNextCandleStart() {
            PatternQueue queue = makeThreeBlockLongQueue(10);
            long startMs = System.currentTimeMillis() - 60_000;
            AutoTradeSessionDTO session = setupSessionWithCondBlock(queue, startMs + 5_000);
            QueueStateDTO state = session.getQueueStates().get(queue.getId());

            autoTradeService.onKlineConfirmed(ExchangeType.BYBIT, makeKline(startMs, "50000", "55000"));

            assertThat(state.getBlockBaseTime()).isEqualTo(toLocalDateTime(startMs + 60_000));
        }

        @Test
        @DisplayName("도지 시 기준 시각을 다음 봉 시작(end+1)으로 맞춘다")
        void dojiSetsBlockBaseToNextCandleStart() {
            PatternQueue queue = makeThreeBlockLongQueue(10);
            long startMs = System.currentTimeMillis() - 60_000;
            AutoTradeSessionDTO session = setupSessionWithCondBlock(queue, startMs + 5_000);
            QueueStateDTO state = session.getQueueStates().get(queue.getId());

            autoTradeService.onKlineConfirmed(ExchangeType.BYBIT, makeKline(startMs, "50000", "50000"));

            assertThat(state.getBlockBaseTime()).isEqualTo(toLocalDateTime(startMs + 60_000));
        }

        @Test
        @DisplayName("패턴 전환 시 기준 시각을 다음 봉 시작(end+1), 기준가를 종가로 맞춘다")
        void switchSetsBlockBaseToNextCandleStart() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            long startMs = System.currentTimeMillis() - 60_000;
            AutoTradeSessionDTO session = setupSessionWithCondBlock(queue, startMs + 5_000);
            QueueStateDTO state = session.getQueueStates().get(queue.getId());
            state.setActivePatternId(100L); // LONG 패턴 매칭 중

            // 음봉 → SHORT 시작 패턴으로 전환
            autoTradeService.onKlineConfirmed(ExchangeType.BYBIT, makeKline(startMs, "50000", "45000"));

            assertThat(state.getActivePatternId()).isEqualTo(200L);
            assertThat(state.getBlockBaseTime()).isEqualTo(toLocalDateTime(startMs + 60_000));
            assertThat(state.getBlockBasePrice()).isEqualTo("45000");
        }

        @Test
        @DisplayName("blockBaseTime이 봉 구간 밖이면 신호를 무시한다")
        void ignoresWhenBlockBaseOutsideCandle() {
            PatternQueue queue = makeThreeBlockLongQueue(10);
            long startMs = System.currentTimeMillis() - 60_000;
            // blockBaseTime이 봉 시작 이전(=다른 봉)
            AutoTradeSessionDTO session = setupSessionWithCondBlock(queue, startMs - 10_000);
            QueueStateDTO state = session.getQueueStates().get(queue.getId());

            autoTradeService.onKlineConfirmed(ExchangeType.BYBIT, makeKline(startMs, "50000", "55000"));

            // blockBaseTime이 봉 구간에 속하지 않으므로 무시 → 변화 없음
            assertThat(state.getCurrentBlockOrder()).isEqualTo(1);
        }

        @Test
        @DisplayName("leaf 블록을 관찰 중이면 onKlineConfirmed에서 무시한다 (진입은 가격 push에서)")
        void ignoresLeafBlock() {
            PatternQueue queue = makeThreeBlockLongQueue(10);
            long startMs = System.currentTimeMillis() - 60_000;
            AutoTradeSessionDTO session = setupSessionWithCondBlock(queue, startMs + 5_000);
            QueueStateDTO state = session.getQueueStates().get(queue.getId());
            // leaf 위치로 변경 (3블록 패턴의 leaf=order 3)
            state.setCurrentBlockOrder(3);

            autoTradeService.onKlineConfirmed(ExchangeType.BYBIT, makeKline(startMs, "50000", "55000"));

            // leaf는 봉 마감 신호로 트리거되지 않음 (가격 push의 즉시 진입에서 처리)
            assertThat(state.getCurrentBlockOrder()).isEqualTo(3);
            then(tradeFacade).should(never()).placeOrder(any(), any(), any(), any(), any());
        }
    }

    // ─────────────────────────────────────────────
    // 포지션 진입 (openPosition)
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("openPosition (leaf 블록 즉시 진입)")
    class OpenPositionTest {

        /**
         * leaf 블록 도달 → 즉시 진입을 트리거하기 위한 공통 setup.
         * processBlockMatching이 호출되면 currentBlockOrder=2(leaf)이므로 60초 안 세고 즉시 openPosition 진입.
         */
        private QueueStateDTO leafReadyState() {
            QueueStateDTO state = QueueStateDTO.initial(1L);
            state.setPhase(TradePhase.BLOCK_MATCHING);
            state.setDirection(Side.LONG);
            state.setActivePatternId(10L);
            state.setCurrentBlockOrder(2); // leaf 위치
            return state;
        }

        @Test
        @DisplayName("진입 직전 거래소에 기존 포지션이 있으면 주문하지 않고 같은 단계의 트리거 대기로 돌아간다")
        void existingPosition_skipsEntryAndReturnsToTriggerWait() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            state.setCurrentStepLevel(2);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(new BigDecimal("0.5"));

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            then(tradeFacade).should(never()).placeOrder(any(), any(), any(), any(), any());
            assertThat(state.getPhase()).isEqualTo(TradePhase.TRIGGER_WAIT);
            assertThat(state.getCurrentStepLevel()).isEqualTo(2);
            assertThat(state.getBasePrice()).isNull();
            assertThat(state.getActivePatternId()).isNull();
            assertThat(state.getEntryPrice()).isNull();
            assertThat(queue.isActive()).isTrue();
            assertThat(session.getTradeLog()).anyMatch(l -> l.contains("[진입 보류]"));
        }

        @Test
        @DisplayName("진입 직전 포지션 조회에 실패하면 이번 진입은 건너뛰고 트리거 대기로 돌아간다")
        void positionCheckFails_skipsEntry() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(null);

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            then(tradeFacade).should(never()).placeOrder(any(), any(), any(), any(), any());
            assertThat(state.getPhase()).isEqualTo(TradePhase.TRIGGER_WAIT);
            assertThat(queue.isActive()).isTrue();
        }

        @Test
        @DisplayName("진입 시 notional(amount × leverage)로 convertUsdtToQty를 호출한다")
        void callsConvertUsdtToQtyWithNotional() {
            // amount=100, leverage=10 → notional=1000
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            ArgumentCaptor<BigDecimal> notionalCaptor = ArgumentCaptor.forClass(BigDecimal.class);
            given(marketService.convertUsdtToQty(eq(ExchangeType.BYBIT), eq("BTCUSDT"), notionalCaptor.capture(), any()))
                    .willReturn("0.002");

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            // amount(100) × leverage(10) = 1000 USDT 로 호출되어야 함
            assertThat(notionalCaptor.getValue()).isEqualByComparingTo(new BigDecimal("1000"));
        }

        @Test
        @DisplayName("진입 성공 시 entryQty/entryMargin/entryTime을 저장하고 POSITION_HOLDING 페이즈로 전환한다")
        void savesEntryQtyAndTransitionsToPositionHolding() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");

            long before = System.currentTimeMillis();
            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            // 진입 주문 전송 시각 기록 (놓친 외부 청산 재조정 시 청산 내역 검색 시작점)
            assertThat(state.getEntryTime()).isBetween(before, System.currentTimeMillis());
            assertThat(state.getEntryQty()).isEqualTo("0.002");
            assertThat(state.getEntryMargin()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(state.getEntryPrice()).isEqualTo("50000.00");
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
        }

        @Test
        @DisplayName("진입 주문 전에 WS 체결 대기를 등록하고, 같은 orderLinkId로 실체결을 조회해 진입가에 반영한다")
        void expectsExecutionBeforeOrderAndUsesOrderLinkId() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");

            OrderResultDTO response = OrderResultDTO.builder().orderId("entry-1").build();
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willReturn(response);

            ExecutionDTO exec = ExecutionDTO.builder().execPrice("50010").execFee("0.055").build();
            given(tradeFacade.getExecution(eq("entry-1"), anyString(), eq("BTCUSDT"), eq(1L), any(), any())).willReturn(exec);

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            // 대기 등록 → 주문 순서 (WS 체결이 REST 응답보다 먼저 올 수 있음)
            ArgumentCaptor<String> linkCaptor = ArgumentCaptor.forClass(String.class);
            InOrder inOrder = inOrder(tradeFacade);
            inOrder.verify(tradeFacade).expectExecution(eq(1L), linkCaptor.capture(), eq(ExchangeType.BYBIT), eq(TradeMode.MAIN));
            inOrder.verify(tradeFacade).placeOrder(argThat(r -> linkCaptor.getValue().equals(r.getClientOrderId())),
                    any(), any(), any(), any());
            inOrder.verify(tradeFacade).getExecution(eq("entry-1"), eq(linkCaptor.getValue()), eq("BTCUSDT"), eq(1L), any(), any());
            assertThat(linkCaptor.getValue()).startsWith("q1-E-");
            assertThat(state.getEntryPrice()).isEqualTo("50010");
        }

        /** placeOrder에 전달된 요청 중 지정가(price 있음) 요청만 추린다 */
        private List<OrderRequestDTO> limitOrders() {
            ArgumentCaptor<OrderRequestDTO> captor = ArgumentCaptor.forClass(OrderRequestDTO.class);
            then(tradeFacade).should(atLeast(0)).placeOrder(captor.capture(), any(), any(), any(), any());
            return captor.getAllValues().stream().filter(r -> r.getPrice() != null).toList();
        }

        @Test
        @DisplayName("익절 지정가: MAIN 진입 직후 익절가(tick 올림)에 reduceOnly 지정가를 걸고 주문 ID를 상태에 보관한다")
        void main_entry_placesTpLimitOrder() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            List<OrderRequestDTO> limits = limitOrders();
            assertThat(limits).hasSize(1);
            OrderRequestDTO tp = limits.get(0);
            assertThat(tp.getSide()).isEqualTo(OrderSide.SELL);
            assertThat(tp.isReduceOnly()).isTrue();
            assertThat(tp.getQty()).isEqualTo("0.002");
            assertThat(tp.getPrice()).isEqualTo(TradeCalculator.roundTpLimitPrice(Side.LONG, state.getTpPrice(),
                    new BigDecimal("0.10")).toPlainString());
            assertThat(tp.getClientOrderId()).startsWith("q1-T-");
            assertThat(state.getTpOrderLinkId()).isEqualTo(tp.getClientOrderId());
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
        }

        @Test
        @DisplayName("익절 지정가: SIM은 지정가를 걸지 않는다 (앱 감시 방식 유지)")
        void sim_entry_noTpLimitOrder() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            session.setTradeMode(TradeMode.SIM);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            assertThat(limitOrders()).isEmpty();
            assertThat(state.getTpOrderLinkId()).isNull();
        }

        @Test
        @DisplayName("SIM 진입: 실거래 지정가와 같은 익절 지정가(tick 반올림)를 미리 계산해 둔다")
        void sim_entry_presetsTpLimitPrice() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            session.setTradeMode(TradeMode.SIM);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            assertThat(state.getTpOrderPrice()).isEqualByComparingTo(
                    TradeCalculator.roundTpLimitPrice(Side.LONG, state.getTpPrice(), new BigDecimal("0.10")));
            assertThat(state.getTpOrderLinkId()).isNull();
        }

        @Test
        @DisplayName("SIM 진입: tickSize 조회 실패해도 진입은 유지 (익절가 그대로 사용)")
        void sim_entry_tickSizeUnavailable_keepsPosition() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            session.setTradeMode(TradeMode.SIM);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            given(marketService.getTickSize(any(), any())).willReturn(null);

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            assertThat(queue.isActive()).isTrue();
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
            assertThat(state.getTpOrderPrice()).isNull();
        }

        @Test
        @DisplayName("익절 지정가: 진입 확인 대기 중에는 걸지 않고, 진입 확인 후에 건다")
        void pendingEntry_placesTpAfterConfirm() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(BigDecimal.ZERO, null, new BigDecimal("0.002"));

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");
            assertThat(limitOrders()).isEmpty();

            state.setCloseConfirmCheckedAt(0L);
            autoTradeService.processPositionHolding(queue, state, session, "50000.00");

            assertThat(limitOrders()).hasSize(1);
            assertThat(state.getTpOrderLinkId()).startsWith("q1-T-");
        }

        @Test
        @DisplayName("익절 지정가 등록 거부(D2): 남은 지정가 취소 시도 → 시장가 청산 → 큐 비활성화")
        void tpRejected_closesMarketAndDeactivates() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            // 지정가만 거래소 거부, 시장가(진입·청산)는 접수
            willAnswer(inv -> {
                OrderRequestDTO req = inv.getArgument(0);
                if (req == null) return null;
                if (req.getPrice() != null) throw new CustomException(ExceptionMessage.ORDER_FAILED, "[110007] insufficient");
                fakePositionSize.set(req.isReduceOnly() ? BigDecimal.ZERO : new BigDecimal(req.getQty()));
                return OrderResultDTO.builder().orderId(req.getClientOrderId()).build();
            }).given(tradeFacade).placeOrder(any(), any(), any(), any(), any());

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            ArgumentCaptor<String> cancelLink = ArgumentCaptor.forClass(String.class);
            then(tradeFacade).should().cancelOrder(eq(1L), eq("BTCUSDT"), cancelLink.capture(), eq(ExchangeType.BYBIT), eq(TradeMode.MAIN));
            assertThat(cancelLink.getValue()).startsWith("q1-T-");
            ArgumentCaptor<OrderRequestDTO> orders = ArgumentCaptor.forClass(OrderRequestDTO.class);
            then(tradeFacade).should(times(3)).placeOrder(orders.capture(), any(), any(), any(), any());
            OrderRequestDTO close = orders.getAllValues().get(2);
            assertThat(close.getPrice()).isNull();
            assertThat(close.isReduceOnly()).isTrue();
            assertThat(close.getClientOrderId()).startsWith("q1-L-");
            assertThat(queue.isActive()).isFalse();
        }

        @Test
        @DisplayName("익절 지정가 가격 산출 불가(tickSize 조회 실패, D2): FAILED 이력 저장 → 시장가 청산 → 큐 비활성화")
        void tpPriceUnavailable_failedHistoryAndClose() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            given(marketService.getTickSize(any(), any())).willReturn(null);

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            assertThat(limitOrders()).isEmpty();
            ArgumentCaptor<TradeHistoryDTO> histories = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should(atLeast(1)).saveTradeHistory(histories.capture(), eq(TradeMode.MAIN));
            assertThat(histories.getAllValues()).anySatisfy(h -> {
                assertThat(h.getOrderResult()).isEqualTo(OrderResult.FAILED);
                assertThat(h.getOrderType()).isEqualTo(TradeOrderType.SELL.getTradeOrderType());
                assertThat(h.getErrorMessage()).startsWith("[익절 지정가 등록 실패]");
            });
            ArgumentCaptor<OrderRequestDTO> orders = ArgumentCaptor.forClass(OrderRequestDTO.class);
            then(tradeFacade).should(times(2)).placeOrder(orders.capture(), any(), any(), any(), any());
            assertThat(orders.getAllValues().get(1).getClientOrderId()).startsWith("q1-L-");
            assertThat(queue.isActive()).isFalse();
        }

        @Test
        @DisplayName("진입 완료 확인: 실체결 조회 실패 + 거래소 포지션 있음 → 보유 전환하고 SUCCESS 이력을 저장한다")
        void execMissing_positionExists_holds() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            // placeOrder 기본 응답이 가짜 포지션 0.002 보유로 갱신, 실체결 조회는 null

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
            assertThat(state.getPendingConfirmType()).isNull();
            ArgumentCaptor<TradeHistoryDTO> captor = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should(times(1)).saveTradeHistory(captor.capture(), eq(TradeMode.MAIN));
            assertThat(captor.getValue().getOrderResult()).isEqualTo(OrderResult.SUCCESS);
        }

        @Test
        @DisplayName("진입 완료 확인: 실체결 조회 실패 + 거래소 포지션 0 → 진입 미체결로 FAILED 이력 저장 후 큐 비활성화")
        void execMissing_positionZero_entryFails() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(BigDecimal.ZERO);

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            assertThat(queue.isActive()).isFalse();
            assertThat(state.getPhase()).isNotEqualTo(TradePhase.POSITION_HOLDING);
            ArgumentCaptor<TradeHistoryDTO> captor = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should(times(1)).saveTradeHistory(captor.capture(), eq(TradeMode.MAIN));
            assertThat(captor.getValue().getOrderResult()).isEqualTo(OrderResult.FAILED);
            assertThat(captor.getValue().getErrorMessage()).startsWith("[진입 미체결]");
        }

        @Test
        @DisplayName("진입 완료 확인: 포지션 조회도 실패하면 이력 저장을 보류하고 확인 대기, 다음 틱에 포지션 확인 시 SUCCESS 저장")
        void execMissing_positionUnknown_waitsThenConfirms() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any()))
                    .willReturn(OrderResultDTO.builder().orderId("entry-1").build());
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(BigDecimal.ZERO, null, new BigDecimal("0.002"));

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            // 확인 대기: 보유 상태지만 이력은 아직 저장하지 않음
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
            assertThat(state.getPendingConfirmType()).isEqualTo(TradeOrderType.ENTRY);
            then(tradeFacade).should(never()).saveTradeHistory(any(), any());

            // 다음 틱: TP 도달 가격이어도 매도하지 않고 진입 확인만 수행
            state.setCloseConfirmCheckedAt(0L);
            autoTradeService.processPositionHolding(queue, state, session, "99999.00");

            // 시장가 주문은 진입 1건뿐 (매도 없음), 진입 확인 후 익절 지정가만 추가
            ArgumentCaptor<OrderRequestDTO> orders = ArgumentCaptor.forClass(OrderRequestDTO.class);
            then(tradeFacade).should(times(2)).placeOrder(orders.capture(), any(), any(), any(), any());
            assertThat(orders.getAllValues()).filteredOn(r -> r.getPrice() == null).hasSize(1);
            assertThat(state.getPendingConfirmType()).isNull();
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
            ArgumentCaptor<TradeHistoryDTO> captor = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should(times(1)).saveTradeHistory(captor.capture(), eq(TradeMode.MAIN));
            assertThat(captor.getValue().getOrderResult()).isEqualTo(OrderResult.SUCCESS);
            assertThat(captor.getValue().getOrderId()).isEqualTo("entry-1");
        }

        @Test
        @DisplayName("진입 완료 확인: 확인 대기 후 포지션 0이 확인되면 FAILED 이력 저장 후 큐 비활성화")
        void execMissing_pendingThenZero_entryFails() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(BigDecimal.ZERO, null, BigDecimal.ZERO);

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");
            state.setCloseConfirmCheckedAt(0L);
            autoTradeService.processPositionHolding(queue, state, session, "50000.00");

            assertThat(queue.isActive()).isFalse();
            ArgumentCaptor<TradeHistoryDTO> captor = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should(times(1)).saveTradeHistory(captor.capture(), eq(TradeMode.MAIN));
            assertThat(captor.getValue().getOrderResult()).isEqualTo(OrderResult.FAILED);
            assertThat(captor.getValue().getErrorMessage()).startsWith("[진입 미체결]");
        }

        @Test
        @DisplayName("진입 완료 확인: SIM은 거래소 포지션 조회 없이 보유 전환한다")
        void sim_entry_noPositionCheck() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            session.setTradeMode(TradeMode.SIM);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            then(tradeFacade).should(never()).getPositionSize(any(), any(), any(), any());
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
        }

        @Test
        @DisplayName("진입 이력 저장 실패: 체결된 포지션은 보유 상태로 유지하고 실패 이력(FAILED)을 fallback 저장한다")
        void entryHistorySaveFails_keepsPositionAndSavesFallback() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any()))
                    .willReturn(OrderResultDTO.builder().orderId("entry-1").build());
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(BigDecimal.ZERO, new BigDecimal("0.002"));
            // 첫 저장(진입 이력)은 DB 오류, 두 번째(fallback)는 성공
            willThrow(new RuntimeException("DB down")).willDoNothing()
                    .given(tradeFacade).saveTradeHistory(any(), any());

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            // 거래소 체결 완료 → 보유 상태 유지, 큐 비활성화하지 않음
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
            assertThat(state.getEntryPrice()).isEqualTo("50000.00");
            assertThat(queue.isActive()).isTrue();
            // fallback: 새 이력 객체로 FAILED + 사유 저장
            ArgumentCaptor<TradeHistoryDTO> captor = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should(times(2)).saveTradeHistory(captor.capture(), eq(TradeMode.MAIN));
            TradeHistoryDTO fallback = captor.getAllValues().get(1);
            assertThat(fallback).isNotSameAs(captor.getAllValues().get(0));
            assertThat(fallback.getOrderResult()).isEqualTo(OrderResult.FAILED);
            assertThat(fallback.getErrorMessage()).startsWith("[이력 저장 실패] 체결은 완료됨").contains("DB down");
            assertThat(fallback.getOrderType()).isEqualTo(TradeOrderType.ENTRY.getTradeOrderType());
            assertThat(fallback.getOrderId()).isEqualTo("entry-1");
            assertThat(fallback.getSymbol()).isEqualTo("BTCUSDT");
            assertThat(fallback.getExchangeType()).isEqualTo(ExchangeType.BYBIT);
        }

        @Test
        @DisplayName("마진 모드 전환 시 진입 이력을 함께 넘기고, 실패하면 주문 없이 큐를 비활성화한다")
        void marginSwitchFails_passesHistory_noOrder_deactivates() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            willThrow(new CustomException(ExceptionMessage.SWITCH_MARGIN_MODE_FAILED, "[3400045] error"))
                    .given(tradeFacade).switchToIsolated(eq(1L), eq("BTCUSDT"), any(), any(), any());

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            // 실패 이력 저장용으로 진입 이력(심볼·진입 유형)이 전달되어야 함
            ArgumentCaptor<TradeHistoryDTO> historyCaptor = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should().switchToIsolated(eq(1L), eq("BTCUSDT"), historyCaptor.capture(),
                    eq(ExchangeType.BYBIT), eq(TradeMode.MAIN));
            assertThat(historyCaptor.getValue().getSymbol()).isEqualTo("BTCUSDT");
            assertThat(historyCaptor.getValue().getOrderType()).isEqualTo(TradeOrderType.ENTRY.getTradeOrderType());
            then(tradeFacade).should(never()).placeOrder(any(), any(), any(), any(), any());
            assertThat(queue.isActive()).isFalse();
        }

        @Test
        @DisplayName("레버리지 설정이 실패하면 주문 없이 큐를 비활성화한다 (이전 레버리지로 진입 방지)")
        void leverageFails_noOrder_deactivates() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            willThrow(new CustomException(ExceptionMessage.SET_LEVERAGE_FAILED, "[10001] error"))
                    .given(tradeFacade).setLeverage(eq(1L), eq("BTCUSDT"), eq("10"), any(), any(), any());

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            then(tradeFacade).should().setLeverage(eq(1L), eq("BTCUSDT"), eq("10"), any(TradeHistoryDTO.class),
                    eq(ExchangeType.BYBIT), eq(TradeMode.MAIN));
            then(tradeFacade).should(never()).placeOrder(any(), any(), any(), any(), any());
            assertThat(queue.isActive()).isFalse();
        }

        @Test
        @DisplayName("진입 주문이 실패하면 WS 체결 대기를 정리한다")
        void cancelsExpectedExecutionOnOrderFailure() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willThrow(new RuntimeException("주문 거부"));

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            ArgumentCaptor<String> linkCaptor = ArgumentCaptor.forClass(String.class);
            then(tradeFacade).should().expectExecution(eq(1L), linkCaptor.capture(), any(), any());
            then(tradeFacade).should().cancelExecution(linkCaptor.getValue(), ExchangeType.BYBIT, TradeMode.MAIN);
        }

        @Test
        @DisplayName("진입 성공 시 closeSkipCount를 0으로 초기화한다")
        void resetsCloseSkipCountOnSuccess() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            state.setCloseSkipCount(3); // 이전에 스킵이 있었던 상태 가정
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            assertThat(state.getCloseSkipCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("LONG 진입 시 leverage 기반으로 TP/SL 가격을 계산한다 (default 100/leverage × 0.8)")
        void calculatesTpSlOnLongEntry() {
            // leverage=10 → default 8% (안전 계수 0.8 적용) → entry=50000, tp=54000, sl=46000
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            assertThat(state.getTpPrice()).isEqualByComparingTo(new BigDecimal("54000.00"));
            assertThat(state.getSlPrice()).isEqualByComparingTo(new BigDecimal("46000.00"));
        }

        @Test
        @DisplayName("SHORT 진입 시 TP는 진입가 미만, SL은 진입가 초과로 계산된다 (안전 계수 0.8 적용)")
        void calculatesTpSlOnShortEntry() {
            // leverage=10 → default 8% → entry=50000, SHORT tp=46000(아래쪽), sl=54000(위쪽)
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.SHORT, 10);
            QueueStateDTO state = leafReadyState();
            state.setDirection(Side.SHORT);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            assertThat(state.getTpPrice()).isEqualByComparingTo(new BigDecimal("46000.00"));
            assertThat(state.getSlPrice()).isEqualByComparingTo(new BigDecimal("54000.00"));
        }

        @Test
        @DisplayName("S:L 패턴 - 트리거 SHORT지만 leaf=LONG이므로 LONG 포지션으로 진입한다")
        void enterLongOnShortLeafLongPattern() {
            // S:L 패턴 = cond(SHORT) + leaf(LONG): 진입 방향은 leaf side(LONG)
            PatternQueue queue = makePatternWithCondAndLeafSides(Side.SHORT, Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            state.setDirection(Side.SHORT); // 트리거가 SHORT 방향이었음을 시뮬레이션
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            // 진입 방향은 leaf side(LONG)로 갱신되어야 함
            assertThat(state.getDirection()).isEqualTo(Side.LONG);
            // TP/SL은 LONG 기준 + 안전 계수 0.8 (default 8%) — 익절가는 진입가보다 높고, 손절가는 낮아야 함
            assertThat(state.getTpPrice()).isEqualByComparingTo(new BigDecimal("54000.00"));
            assertThat(state.getSlPrice()).isEqualByComparingTo(new BigDecimal("46000.00"));
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
            // 주문 side 검증: LONG → BUY
            ArgumentCaptor<OrderRequestDTO> orderCaptor = ArgumentCaptor.forClass(OrderRequestDTO.class);
            then(tradeFacade).should(atLeast(1)).placeOrder(orderCaptor.capture(), any(), any(), any(), any());
            assertThat(orderCaptor.getAllValues().get(0).getSide()).isEqualTo(OrderSide.BUY);
        }

        @Test
        @DisplayName("L:S 패턴 - 트리거 LONG지만 leaf=SHORT이므로 SHORT 포지션으로 진입한다")
        void enterShortOnLongLeafShortPattern() {
            // L:S 패턴 = cond(LONG) + leaf(SHORT): 진입 방향은 leaf side(SHORT)
            PatternQueue queue = makePatternWithCondAndLeafSides(Side.LONG, Side.SHORT, 10);
            QueueStateDTO state = leafReadyState();
            state.setDirection(Side.LONG); // 트리거가 LONG 방향이었음을 시뮬레이션
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            // 진입 방향은 leaf side(SHORT)로 갱신
            assertThat(state.getDirection()).isEqualTo(Side.SHORT);
            // TP/SL은 SHORT 기준 + 안전 계수 0.8 (default 8%) — 익절가는 진입가보다 낮고, 손절가는 높아야 함
            assertThat(state.getTpPrice()).isEqualByComparingTo(new BigDecimal("46000.00"));
            assertThat(state.getSlPrice()).isEqualByComparingTo(new BigDecimal("54000.00"));
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
            // 주문 side 검증: SHORT → SELL
            ArgumentCaptor<OrderRequestDTO> orderCaptor = ArgumentCaptor.forClass(OrderRequestDTO.class);
            then(tradeFacade).should(atLeast(1)).placeOrder(orderCaptor.capture(), any(), any(), any(), any());
            assertThat(orderCaptor.getAllValues().get(0).getSide()).isEqualTo(OrderSide.SELL);
        }

        // ── 투자금 기준 TP/SL + 거래소 안전망 TP/SL ──

        /** 큐의 첫 패턴에 익절/손절률(투자금 기준 %) 설정 */
        private void setRates(PatternQueue queue, String tpRate, String slRate) {
            Pattern pattern = queue.getPatterns().get(0);
            pattern.setTakeProfitRate(tpRate != null ? new BigDecimal(tpRate) : null);
            pattern.setStopLossRate(slRate != null ? new BigDecimal(slRate) : null);
        }

        /** placeOrder로 전달된 진입 주문 캡처 */
        private OrderRequestDTO capturedEntryOrder() {
            ArgumentCaptor<OrderRequestDTO> orderCaptor = ArgumentCaptor.forClass(OrderRequestDTO.class);
            // 진입 주문 = 첫 주문 (이후 익절 지정가 주문이 이어질 수 있음)
            then(tradeFacade).should(atLeast(1)).placeOrder(orderCaptor.capture(), any(), any(), any(), any());
            return orderCaptor.getAllValues().get(0);
        }

        @Test
        @DisplayName("익절/손절률은 투자금 기준 — 10배, 익절 50% / 손절 30% → 가격 +5% / -3%")
        void tpSlRatesAreRoiBased() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            setRates(queue, "50", "30");
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            assertThat(state.getTpPrice()).isEqualByComparingTo("52500");
            assertThat(state.getSlPrice()).isEqualByComparingTo("48500");
        }

        @Test
        @DisplayName("진입 주문에 orderLinkId(q{큐ID}-E-{ms}, 36자 이하)를 부여한다")
        void entryOrderHasOrderLinkId() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            String orderLinkId = capturedEntryOrder().getClientOrderId();
            assertThat(orderLinkId).matches("q1-E-\\d{13}");
            assertThat(orderLinkId.length()).isLessThanOrEqualTo(36);
        }

        @Test
        @DisplayName("LONG 진입 주문에 거래소 TP/SL(앱 기준 + 가격 0.5%p)을 함께 보낸다")
        void attachesExchangeTpSlOnLongEntry() {
            // 10배, 미입력 → 앱 8% / 거래소 8.5% → TP 54250, SL 45750
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            given(marketService.getTickSize(ExchangeType.BYBIT, "BTCUSDT")).willReturn("0.10");

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            OrderRequestDTO order = capturedEntryOrder();
            assertThat(new BigDecimal(order.getTakeProfit())).isEqualByComparingTo("54250");
            assertThat(new BigDecimal(order.getStopLoss())).isEqualByComparingTo("45750");
            // 주문 방식(Full/Market/LastPrice) 지정은 거래소 구현체 책임 (BybitTradeClientTest에서 검증)
            // 앱 TP/SL은 기존대로 (거래소보다 안쪽)
            assertThat(state.getTpPrice()).isEqualByComparingTo("54000");
            assertThat(state.getSlPrice()).isEqualByComparingTo("46000");
        }

        @Test
        @DisplayName("SHORT 진입 주문의 거래소 TP는 진입가 아래, SL은 위")
        void attachesExchangeTpSlOnShortEntry() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.SHORT, 10);
            QueueStateDTO state = leafReadyState();
            state.setDirection(Side.SHORT);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            given(marketService.getTickSize(ExchangeType.BYBIT, "BTCUSDT")).willReturn("0.10");

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            OrderRequestDTO order = capturedEntryOrder();
            assertThat(new BigDecimal(order.getTakeProfit())).isEqualByComparingTo("45750");
            assertThat(new BigDecimal(order.getStopLoss())).isEqualByComparingTo("54250");
        }

        @Test
        @DisplayName("tickSize 조회 실패 시 거래소 TP/SL 없이 진입하고 경고 로그를 남긴다")
        void noTickSize_entersWithoutExchangeTpSl() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            // 진입 시 안전망 TP/SL용 조회만 실패, 이후 익절 지정가용 조회는 성공
            given(marketService.getTickSize(ExchangeType.BYBIT, "BTCUSDT")).willReturn(null, "0.10");

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            OrderRequestDTO order = capturedEntryOrder();
            assertThat(order.getTakeProfit()).isNull();
            assertThat(order.getStopLoss()).isNull();
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
            assertThat(session.getTradeLog()).anyMatch(l -> l.contains("[경고]") && l.contains("안전"));
        }

        @Test
        @DisplayName("손절률이 강제청산 한도를 넘는 기존 패턴은 거래소 SL만 빼고 진입한다 (TP는 유지)")
        void stopLossOverLimit_omitsExchangeSlOnly() {
            // 10배 한도 85% → 손절 90%는 초과
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            setRates(queue, null, "90");
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            given(marketService.getTickSize(ExchangeType.BYBIT, "BTCUSDT")).willReturn("0.10");

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            OrderRequestDTO order = capturedEntryOrder();
            assertThat(order.getStopLoss()).isNull();
            assertThat(new BigDecimal(order.getTakeProfit())).isEqualByComparingTo("54250");
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
            assertThat(session.getTradeLog()).anyMatch(l -> l.contains("[경고]") && l.contains("강제청산"));
        }
    }

    // ─────────────────────────────────────────────
    // 블록 매칭 (processBlockMatching)
    // ─────────────────────────────────────────────

    /**
     * 3블록 패턴 헬퍼: cond(L)/cond(L)/leaf(L) — BlockMatchingTest, OnKlineConfirmedTest 공용
     */
    private PatternQueue makeThreeBlockLongQueue(int leverage) {
        PatternQueue queue = new PatternQueue();
        queue.setExchangeType(ExchangeType.BYBIT);
        queue.setId(1L);
        queue.setUserId(1L);
        queue.setSymbol("BTCUSDT");
        queue.setActive(true);
        queue.setTriggerRate(new BigDecimal("1.0"));
        queue.setFull(false);
        ReflectionTestUtils.setField(queue, "createdAt", LocalDateTime.now());

        queue.getPatterns().add(makePattern(queue, 10L, 1, 1, leverage,
                Side.LONG, Side.LONG, Side.LONG));  // cond(L) / cond(L) / leaf(L)
        return queue;
    }

    @Nested
    @DisplayName("processBlockMatching (조건 블록 → 봉 마감 대기 셋업)")
    class BlockMatchingTest {

        @Test
        @DisplayName("조건 블록 도달 시 blockBaseTime이 null이면 새로 세팅하고 종료한다 (신호 판단은 onKlineConfirmed)")
        void setsBlockBaseTimeOnFirstHit() {
            PatternQueue queue = makeThreeBlockLongQueue(10);
            QueueStateDTO state = QueueStateDTO.initial(1L);
            state.setPhase(TradePhase.BLOCK_MATCHING);
            state.setDirection(Side.LONG);
            state.setActivePatternId(10L);
            state.setCurrentBlockOrder(2);
            // blockBaseTime은 null 상태
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            autoTradeService.processBlockMatching(queue, state, session, "50100.00");

            // blockBaseTime/Price는 세팅되고, 블록 위치는 변하지 않음 (신호 판단은 봉 마감 시)
            assertThat(state.getBlockBaseTime()).isNotNull();
            assertThat(state.getBlockBasePrice()).isEqualTo("50100.00");
            assertThat(state.getCurrentBlockOrder()).isEqualTo(2);
            assertThat(state.getPhase()).isEqualTo(TradePhase.BLOCK_MATCHING);
            then(tradeFacade).should(never()).placeOrder(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("blockBaseTime이 2분 넘게 지났으면(봉 마감 신호 누락) 기준 시각/가격을 현재로 재설정한다")
        void resetsStaleBlockBaseTime() {
            PatternQueue queue = makeThreeBlockLongQueue(10);
            QueueStateDTO state = QueueStateDTO.initial(1L);
            state.setPhase(TradePhase.BLOCK_MATCHING);
            state.setDirection(Side.LONG);
            state.setActivePatternId(10L);
            state.setCurrentBlockOrder(2);
            // WebSocket 단절 등으로 봉 마감 신호를 놓쳐 오래된 기준 시각이 남은 상태
            state.setBlockBaseTime(LocalDateTime.now().minusMinutes(3));
            state.setBlockBasePrice("50000.00");
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            autoTradeService.processBlockMatching(queue, state, session, "50100.00");

            // 기준 시각/가격이 현재로 재설정되고 블록 위치는 유지 (다음 봉 마감에서 재판정)
            assertThat(state.getBlockBaseTime()).isAfter(LocalDateTime.now().minusSeconds(5));
            assertThat(state.getBlockBasePrice()).isEqualTo("50100.00");
            assertThat(state.getCurrentBlockOrder()).isEqualTo(2);
            then(tradeFacade).should(never()).placeOrder(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("조건 블록에서 blockBaseTime이 이미 세팅되어 있으면 처리하지 않는다")
        void doesNothingWhenBlockBaseAlreadySet() {
            PatternQueue queue = makeThreeBlockLongQueue(10);
            QueueStateDTO state = QueueStateDTO.initial(1L);
            state.setPhase(TradePhase.BLOCK_MATCHING);
            state.setDirection(Side.LONG);
            state.setActivePatternId(10L);
            state.setCurrentBlockOrder(2);
            LocalDateTime origBase = LocalDateTime.now().minusSeconds(10);
            state.setBlockBaseTime(origBase);
            state.setBlockBasePrice("50000.00");
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            autoTradeService.processBlockMatching(queue, state, session, "55000.00");

            // 기존 값 변화 없음
            assertThat(state.getBlockBaseTime()).isEqualTo(origBase);
            assertThat(state.getBlockBasePrice()).isEqualTo("50000.00");
            assertThat(state.getCurrentBlockOrder()).isEqualTo(2);
            then(tradeFacade).should(never()).placeOrder(any(), any(), any(), any(), any());
        }
    }


    // ─────────────────────────────────────────────
    // 포지션 보유 중 (processPositionHolding) - TP/SL 트리거
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("processPositionHolding (TP/SL 트리거)")
    class PositionHoldingTest {

        /**
         * 진입 완료 직후 상태를 시뮬레이션하는 헬퍼.
         * - phase=POSITION_HOLDING / direction LONG / entryPrice=50000
         * - tpPrice/slPrice는 leverage=10 기준 (default 10%) 반영
         */
        private QueueStateDTO holdingState(Side direction, BigDecimal tpPrice, BigDecimal slPrice) {
            QueueStateDTO state = QueueStateDTO.initial(1L);
            state.setPhase(TradePhase.POSITION_HOLDING);
            state.setDirection(direction);
            state.setActivePatternId(10L);
            state.setEntryPrice("50000.00");
            state.setEntryQty("0.001");
            state.setEntryMargin(new BigDecimal("100"));
            state.setTpPrice(tpPrice);
            state.setSlPrice(slPrice);
            state.setCurrentBlockOrder(2);
            return state;
        }

        @Test
        @DisplayName("LONG 포지션 + 현재가가 TP/SL 사이면 아무 작업도 하지 않는다")
        void longBetweenTpSl_doesNothing() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = holdingState(Side.LONG,
                    new BigDecimal("55000"), new BigDecimal("45000"));
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            // 50000 → 51000: TP 미달, SL 미달 → 대기
            autoTradeService.processPositionHolding(queue, state, session, "51000.00");

            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
            then(tradeFacade).should(never()).placeOrder(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("LONG TP 도달 시 매도 성공 → 1단계 LONG 패턴으로 재진입 (currentBlockOrder=2)")
        void longTpReached_sellAndReenterStep1Long() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG,
                    new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L); // LONG 패턴
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            // TP 도달
            autoTradeService.processPositionHolding(queue, state, session, "55500.00");

            // 매도 주문 + 거래/투자 이력 저장
            then(tradeFacade).should(times(1)).placeOrder(any(), any(), any(TradeHistoryDTO.class), any(), any());
            then(tradeFacade).should(times(1)).saveTradeHistory(any(TradeHistoryDTO.class), any());
            then(tradeFacade).should(times(1)).saveInvestmentHistory(any(InvestmentHistoryDTO.class), any());

            // 1단계 LONG 재진입, currentBlockOrder=2 (첫 블록 자동 매칭)
            assertThat(state.getCurrentStepLevel()).isEqualTo(1);
            assertThat(state.getDirection()).isEqualTo(Side.LONG);
            assertThat(state.getActivePatternId()).isEqualTo(100L);
            assertThat(state.getCurrentBlockOrder()).isEqualTo(2);
            assertThat(state.getPhase()).isEqualTo(TradePhase.BLOCK_MATCHING);
            // TP/SL 가격은 리셋되어야 함
            assertThat(state.getTpPrice()).isNull();
            assertThat(state.getSlPrice()).isNull();
        }

        @Test
        @DisplayName("청산 시 거래·투자 이력에 세션의 거래소 타입을 기록한다")
        void close_recordsSessionExchangeTypeInHistories() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG,
                    new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            session.setExchangeType(ExchangeType.BYBIT);

            // TP 도달 → 매도 + 이력 저장
            autoTradeService.processPositionHolding(queue, state, session, "55500.00");

            ArgumentCaptor<TradeHistoryDTO> tradeCaptor = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should(times(1)).saveTradeHistory(tradeCaptor.capture(), any());
            assertThat(tradeCaptor.getValue().getExchangeType()).isEqualTo(ExchangeType.BYBIT);

            ArgumentCaptor<InvestmentHistoryDTO> investCaptor = ArgumentCaptor.forClass(InvestmentHistoryDTO.class);
            then(tradeFacade).should(times(1)).saveInvestmentHistory(investCaptor.capture(), any());
            assertThat(investCaptor.getValue().getExchangeType()).isEqualTo(ExchangeType.BYBIT);
        }

        @Test
        @DisplayName("SIM 익절: 현재가가 익절가를 넘어서도 체결가는 익절 지정가, 수수료는 maker로 기록")
        void sim_takeProfit_recordsLimitPriceAndMakerFee() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            state.setTpOrderPrice(new BigDecimal("55000.10")); // 진입 시 미리 계산된 지정가
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            session.setTradeMode(TradeMode.SIM);

            autoTradeService.processPositionHolding(queue, state, session, "55030.00");

            ArgumentCaptor<TradeHistoryDTO> tradeCaptor = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should(times(1)).saveTradeHistory(tradeCaptor.capture(), any());
            assertThat(tradeCaptor.getValue().getExecutedPrice()).isEqualByComparingTo("55000.10");
            // 55000.10 × 0.001 × maker 0.0002 = 0.01100002 → 0.0110 (taker였다면 0.0303)
            assertThat(tradeCaptor.getValue().getFee()).isEqualByComparingTo("0.0110");
            ArgumentCaptor<InvestmentHistoryDTO> investCaptor = ArgumentCaptor.forClass(InvestmentHistoryDTO.class);
            then(tradeFacade).should(times(1)).saveInvestmentHistory(investCaptor.capture(), any());
            assertThat(investCaptor.getValue().getExitPrice()).isEqualByComparingTo("55000.10");
            // 익절 판단·전환은 기존과 동일
            assertThat(state.getPhase()).isEqualTo(TradePhase.BLOCK_MATCHING);
            assertThat(state.getCurrentStepLevel()).isEqualTo(1);
        }

        @Test
        @DisplayName("SIM 익절: 미리 계산된 지정가가 없으면 익절가로 기록")
        void sim_takeProfit_noPresetPrice_usesTpPrice() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.SHORT, new BigDecimal("45000"), new BigDecimal("55000"));
            state.setActivePatternId(100L);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            session.setTradeMode(TradeMode.SIM);

            autoTradeService.processPositionHolding(queue, state, session, "44970.00");

            ArgumentCaptor<TradeHistoryDTO> tradeCaptor = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should(times(1)).saveTradeHistory(tradeCaptor.capture(), any());
            assertThat(tradeCaptor.getValue().getExecutedPrice()).isEqualByComparingTo("45000");
            // 45000 × 0.001 × 0.0002 = 0.009
            assertThat(tradeCaptor.getValue().getFee()).isEqualByComparingTo("0.0090");
        }

        @Test
        @DisplayName("LONG SL 도달 시 청산(SHORT 신호) → 2단계 SHORT 패턴 이동 (currentBlockOrder=2)")
        void longSlReached_liquidateAndMoveToStep2Short() {
            PatternQueue queue = makeTwoStepQueue(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG,
                    new BigDecimal("55000"), new BigDecimal("45000"));
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            // SL 도달
            autoTradeService.processPositionHolding(queue, state, session, "44500.00");

            // 청산 주문 + 거래/투자 이력 저장
            then(tradeFacade).should(times(1)).placeOrder(any(), any(), any(), any(), any());
            then(tradeFacade).should(times(1)).saveInvestmentHistory(any(InvestmentHistoryDTO.class), any());

            // 2단계 SHORT 이동, currentBlockOrder=2
            assertThat(state.getCurrentStepLevel()).isEqualTo(2);
            assertThat(state.getDirection()).isEqualTo(Side.SHORT);
            assertThat(state.getCurrentBlockOrder()).isEqualTo(2);
            assertThat(state.getPhase()).isEqualTo(TradePhase.BLOCK_MATCHING);
            assertThat(state.getTpPrice()).isNull();
            assertThat(state.getSlPrice()).isNull();
        }

        @Test
        @DisplayName("매도 주문에 orderLinkId(q{큐ID}-S-{ms})를 부여한다")
        void sellOrderHasOrderLinkId() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG,
                    new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            // TP 도달 → 매도
            autoTradeService.processPositionHolding(queue, state, session, "55500.00");

            ArgumentCaptor<OrderRequestDTO> orderCaptor = ArgumentCaptor.forClass(OrderRequestDTO.class);
            then(tradeFacade).should().placeOrder(orderCaptor.capture(), any(), any(), any(), any());
            assertThat(orderCaptor.getValue().getClientOrderId()).matches("q1-S-\\d{13}");
        }

        @Test
        @DisplayName("청산 주문에 orderLinkId(q{큐ID}-L-{ms})를 부여한다")
        void liquidationOrderHasOrderLinkId() {
            PatternQueue queue = makeTwoStepQueue(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG,
                    new BigDecimal("55000"), new BigDecimal("45000"));
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            // SL 도달 → 청산
            autoTradeService.processPositionHolding(queue, state, session, "44500.00");

            ArgumentCaptor<OrderRequestDTO> orderCaptor = ArgumentCaptor.forClass(OrderRequestDTO.class);
            then(tradeFacade).should().placeOrder(orderCaptor.capture(), any(), any(), any(), any());
            assertThat(orderCaptor.getValue().getClientOrderId()).matches("q1-L-\\d{13}");
        }

        @Test
        @DisplayName("SHORT TP 도달 시 매도 성공 → 1단계 SHORT 패턴으로 재진입 (currentBlockOrder=2)")
        void shortTpReached_sellAndReenterStep1Short() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.SHORT,
                    new BigDecimal("45000"), new BigDecimal("55000"));
            state.setActivePatternId(200L); // SHORT 패턴
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            // SHORT TP는 가격 하락 → SL 미만으로
            autoTradeService.processPositionHolding(queue, state, session, "44500.00");

            then(tradeFacade).should(times(1)).placeOrder(any(), any(), any(), any(), any());
            then(tradeFacade).should(times(1)).saveInvestmentHistory(any(InvestmentHistoryDTO.class), any());
            assertThat(state.getCurrentStepLevel()).isEqualTo(1);
            assertThat(state.getDirection()).isEqualTo(Side.SHORT);
            assertThat(state.getActivePatternId()).isEqualTo(200L);
            assertThat(state.getCurrentBlockOrder()).isEqualTo(2);
        }

        @Test
        @DisplayName("SHORT SL 도달 시 청산(LONG 신호) → 2단계 LONG 패턴 이동 (currentBlockOrder=2)")
        void shortSlReached_liquidateAndMoveToStep2Long() {
            PatternQueue queue = makeTwoStepQueue(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.SHORT,
                    new BigDecimal("45000"), new BigDecimal("55000"));
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            // SHORT SL은 가격 상승 → SL 초과
            autoTradeService.processPositionHolding(queue, state, session, "55500.00");

            then(tradeFacade).should(times(1)).placeOrder(any(), any(), any(), any(), any());
            then(tradeFacade).should(times(1)).saveInvestmentHistory(any(InvestmentHistoryDTO.class), any());
            assertThat(state.getCurrentStepLevel()).isEqualTo(2);
            assertThat(state.getDirection()).isEqualTo(Side.LONG);
            assertThat(state.getCurrentBlockOrder()).isEqualTo(2);
        }

        @Test
        @DisplayName("마지막 단계 SL 도달 시 큐가 비활성화된다")
        void lastStepSl_deactivatesQueue() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = holdingState(Side.LONG,
                    new BigDecimal("55000"), new BigDecimal("45000"));
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            autoTradeService.processPositionHolding(queue, state, session, "44500.00");

            then(tradeFacade).should(times(1)).placeOrder(any(), any(), any(), any(), any());
            assertThat(queue.isActive()).isFalse();
            then(patternQueueRepository).should(times(1)).save(queue);
        }

        @Test
        @DisplayName("매도 시 entryQty가 null이면 closeSkipCount를 증가시키고 매도를 스킵한다")
        void sellSkips_whenEntryQtyIsNull() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = holdingState(Side.LONG,
                    new BigDecimal("55000"), new BigDecimal("45000"));
            state.setEntryQty(null);    // 비정상 상태 시뮬레이션
            state.setCloseSkipCount(0);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            // TP 도달 → 매도 시도하지만 entryQty null로 skip
            autoTradeService.processPositionHolding(queue, state, session, "55500.00");

            then(tradeFacade).should(never()).placeOrder(any(), any(), any(), any(), any());
            assertThat(state.getCloseSkipCount()).isEqualTo(1);
            assertThat(queue.isActive()).isTrue();
        }

        @Test
        @DisplayName("매도 시 entryQty null 스킵이 5회 도달하면 큐를 비활성화한다")
        void sellDeactivatesQueue_whenSkipCountReaches5() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = holdingState(Side.LONG,
                    new BigDecimal("55000"), new BigDecimal("45000"));
            state.setEntryQty(null);
            state.setCloseSkipCount(4);  // 다음 호출에서 5회 도달
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            autoTradeService.processPositionHolding(queue, state, session, "55500.00");

            assertThat(queue.isActive()).isFalse();
            then(patternQueueRepository).should(times(1)).save(queue);
            then(tradeFacade).should(never()).placeOrder(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("청산 시 entryQty가 null이면 closeSkipCount를 증가시키고 청산을 스킵한다")
        void liquidationSkips_whenEntryQtyIsNull() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = holdingState(Side.LONG,
                    new BigDecimal("55000"), new BigDecimal("45000"));
            state.setEntryQty(null);
            state.setCloseSkipCount(0);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            // SL 도달 → 청산 시도하지만 entryQty null로 skip
            autoTradeService.processPositionHolding(queue, state, session, "44500.00");

            then(tradeFacade).should(never()).placeOrder(any(), any(), any(), any(), any());
            assertThat(state.getCloseSkipCount()).isEqualTo(1);
            assertThat(queue.isActive()).isTrue();
        }

        /** 청산 주문 응답 (orderId 포함) */
        private OrderResultDTO closeOrderResponse(String orderId) {
            return OrderResultDTO.builder().orderId(orderId).build();
        }

        /** 실행기에 제출된 후처리 작업을 실행하지 않고 보관하도록 설정 */
        private ArgumentCaptor<Runnable> holdCloseRecordTask() {
            willDoNothing().given(closeRecordExecutor).execute(any(Runnable.class));
            return ArgumentCaptor.forClass(Runnable.class);
        }

        @Test
        @DisplayName("매도: 주문 후 체결 조회·이력 저장은 실행기에 위임하고 상태 전환은 즉시 완료한다")
        void sell_delegatesRecordToExecutor() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG,
                    new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willReturn(closeOrderResponse("close-1"));
            ArgumentCaptor<Runnable> task = holdCloseRecordTask();

            autoTradeService.processPositionHolding(queue, state, session, "55500.00");

            // 주문만 동기 실행, 체결 조회·이력 저장은 아직 수행되지 않음
            then(tradeFacade).should(times(1)).placeOrder(any(), any(), any(), any(), any());
            then(closeRecordExecutor).should(times(1)).execute(task.capture());
            then(tradeFacade).should(never()).getExecution(any(), any(), any(), any(), any(), any());
            then(tradeFacade).should(never()).saveTradeHistory(any(), any());
            then(tradeFacade).should(never()).saveInvestmentHistory(any(), any());
            // 상태 전환은 이미 완료
            assertThat(state.getPhase()).isEqualTo(TradePhase.BLOCK_MATCHING);
            assertThat(state.getEntryPrice()).isNull();
            assertThat(state.getEntryMargin()).isNull();
        }

        @Test
        @DisplayName("매도: 상태 리셋 후 후처리가 실행돼도 진입 시점 값으로 이력을 저장하고 체결 확정 로그를 남긴다")
        void sell_recordUsesSnapshotAfterStateReset() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG,
                    new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            state.setEntryTime(1_700_000_000_000L);
            state.setEntryFee(new BigDecimal("0.055"));
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willReturn(closeOrderResponse("close-1"));
            ArgumentCaptor<Runnable> task = holdCloseRecordTask();

            autoTradeService.processPositionHolding(queue, state, session, "55500.00");
            then(closeRecordExecutor).should().execute(task.capture());
            task.getValue().run(); // 상태 리셋 이후 후처리 실행

            then(tradeFacade).should(times(1)).getExecution(eq("close-1"), anyString(), eq("BTCUSDT"), eq(1L), any(), any());
            // 손익 조회 조건: 진입 시점 스냅샷(진입 시각·진입 수수료)까지 담아 전달
            ArgumentCaptor<ClosedPnlQueryDTO> queryCaptor = ArgumentCaptor.forClass(ClosedPnlQueryDTO.class);
            then(tradeFacade).should(times(1)).getClosedPnl(queryCaptor.capture(), eq(1L), eq(TradeMode.MAIN));
            ClosedPnlQueryDTO query = queryCaptor.getValue();
            assertThat(query.getExchangeType()).isEqualTo(ExchangeType.BYBIT);
            assertThat(query.getOrderId()).isEqualTo("close-1");
            assertThat(query.getSymbol()).isEqualTo("BTCUSDT");
            assertThat(query.getEntryTime()).isEqualTo(1_700_000_000_000L);
            assertThat(query.getEntryFee()).isEqualByComparingTo("0.055");
            ArgumentCaptor<InvestmentHistoryDTO> captor = ArgumentCaptor.forClass(InvestmentHistoryDTO.class);
            then(tradeFacade).should(times(1)).saveInvestmentHistory(captor.capture(), any());
            InvestmentHistoryDTO saved = captor.getValue();
            assertThat(saved.getEntryPrice()).isEqualByComparingTo("50000");
            assertThat(saved.getAmount()).isEqualByComparingTo("100");
            assertThat(saved.getSide()).isEqualTo(Side.LONG);
            assertThat(saved.getPatternId()).isEqualTo(100L);
            assertThat(session.getTradeLog()).anyMatch(l -> l.contains("[체결 확정]"));
        }

        @Test
        @DisplayName("매도: 투자 이력의 익절/손절 예상가도 투자금 기준으로 계산한다")
        void sell_expectedPricesAreRoiBased() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            // 10배, 익절 50% / 손절 30% → 진입가 50000 기준 52500 / 48500
            queue.getPatterns().forEach(p -> {
                p.setTakeProfitRate(new BigDecimal("50"));
                p.setStopLossRate(new BigDecimal("30"));
            });
            QueueStateDTO state = holdingState(Side.LONG,
                    new BigDecimal("52500"), new BigDecimal("48500"));
            state.setActivePatternId(100L);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willReturn(closeOrderResponse("close-1"));

            autoTradeService.processPositionHolding(queue, state, session, "52600.00");

            ArgumentCaptor<InvestmentHistoryDTO> captor = ArgumentCaptor.forClass(InvestmentHistoryDTO.class);
            then(tradeFacade).should(times(1)).saveInvestmentHistory(captor.capture(), any());
            assertThat(captor.getValue().getTpPrice()).isEqualByComparingTo("52500");
            assertThat(captor.getValue().getSlPrice()).isEqualByComparingTo("48500");
        }

        @Test
        @DisplayName("청산: 다음 단계로 이동한 뒤 후처리가 실행돼도 청산 전 포지션 값으로 이력을 저장한다")
        void liquidation_recordUsesSnapshotAfterStateMove() {
            PatternQueue queue = makeTwoStepQueue(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG,
                    new BigDecimal("55000"), new BigDecimal("45000"));
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willReturn(closeOrderResponse("close-2"));
            ArgumentCaptor<Runnable> task = holdCloseRecordTask();

            autoTradeService.processPositionHolding(queue, state, session, "44500.00");
            // 후처리 실행 전 이미 2단계 SHORT로 이동
            assertThat(state.getCurrentStepLevel()).isEqualTo(2);
            assertThat(state.getDirection()).isEqualTo(Side.SHORT);
            then(closeRecordExecutor).should().execute(task.capture());
            task.getValue().run();

            ArgumentCaptor<InvestmentHistoryDTO> captor = ArgumentCaptor.forClass(InvestmentHistoryDTO.class);
            then(tradeFacade).should(times(1)).saveInvestmentHistory(captor.capture(), any());
            InvestmentHistoryDTO saved = captor.getValue();
            assertThat(saved.getSide()).isEqualTo(Side.LONG);
            assertThat(saved.getEntryPrice()).isEqualByComparingTo("50000");
            assertThat(saved.getAmount()).isEqualByComparingTo("100");
            assertThat(saved.getPatternId()).isEqualTo(10L);
        }

        @Test
        @DisplayName("후처리 중 예외가 나도 큐는 비활성화되지 않고 경고 로그만 남긴다")
        void recordFailure_doesNotDeactivateQueue() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG,
                    new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willReturn(closeOrderResponse("close-1"));
            given(tradeFacade.getExecution(any(), any(), any(), any(), any(), any())).willThrow(new RuntimeException("timeout"));

            autoTradeService.processPositionHolding(queue, state, session, "55500.00");

            assertThat(queue.isActive()).isTrue();
            assertThat(state.getPhase()).isEqualTo(TradePhase.BLOCK_MATCHING);
            then(patternQueueRepository).should(never()).save(queue);
            assertThat(session.getTradeLog()).anyMatch(l -> l.contains("[경고]") && l.contains("이력 저장 실패"));
        }

        @Test
        @DisplayName("매도: 주문 전에 WS 체결 대기를 등록하고 후처리에서 같은 orderLinkId로 체결을 조회한다")
        void sell_expectsExecutionBeforeOrderAndRecordsWithOrderLinkId() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG,
                    new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willReturn(closeOrderResponse("close-1"));

            autoTradeService.processPositionHolding(queue, state, session, "55500.00");

            // 대기 등록 → 주문 → (후처리) 같은 orderLinkId로 체결 조회
            ArgumentCaptor<String> linkCaptor = ArgumentCaptor.forClass(String.class);
            InOrder inOrder = inOrder(tradeFacade);
            inOrder.verify(tradeFacade).expectExecution(eq(1L), linkCaptor.capture(), eq(ExchangeType.BYBIT), eq(TradeMode.MAIN));
            inOrder.verify(tradeFacade).placeOrder(any(), any(), any(), any(), any());
            inOrder.verify(tradeFacade).getExecution(eq("close-1"), eq(linkCaptor.getValue()), eq("BTCUSDT"), eq(1L), any(), any());
            assertThat(linkCaptor.getValue()).startsWith("q1-S-");
        }

        @Test
        @DisplayName("청산: 주문 전에 WS 체결 대기를 등록하고 후처리에서 같은 orderLinkId로 체결을 조회한다")
        void liquidation_expectsExecutionBeforeOrderAndRecordsWithOrderLinkId() {
            PatternQueue queue = makeTwoStepQueue(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG,
                    new BigDecimal("55000"), new BigDecimal("45000"));
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willReturn(closeOrderResponse("close-2"));

            autoTradeService.processPositionHolding(queue, state, session, "44500.00");

            ArgumentCaptor<String> linkCaptor = ArgumentCaptor.forClass(String.class);
            InOrder inOrder = inOrder(tradeFacade);
            inOrder.verify(tradeFacade).expectExecution(eq(1L), linkCaptor.capture(), eq(ExchangeType.BYBIT), eq(TradeMode.MAIN));
            inOrder.verify(tradeFacade).placeOrder(any(), any(), any(), any(), any());
            inOrder.verify(tradeFacade).getExecution(eq("close-2"), eq(linkCaptor.getValue()), eq("BTCUSDT"), eq(1L), any(), any());
            assertThat(linkCaptor.getValue()).startsWith("q1-L-");
        }

        @Test
        @DisplayName("매도 주문 실패 시 WS 체결 대기를 취소한다")
        void sell_cancelsExpectedExecutionOnOrderFailure() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG,
                    new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willThrow(new RuntimeException("주문 거부"));

            autoTradeService.processPositionHolding(queue, state, session, "55500.00");

            ArgumentCaptor<String> linkCaptor = ArgumentCaptor.forClass(String.class);
            then(tradeFacade).should().expectExecution(eq(1L), linkCaptor.capture(), any(), any());
            then(tradeFacade).should().cancelExecution(linkCaptor.getValue(), ExchangeType.BYBIT, TradeMode.MAIN);
        }

        @Test
        @DisplayName("청산 주문 실패 시 WS 체결 대기를 취소한다")
        void liquidation_cancelsExpectedExecutionOnOrderFailure() {
            PatternQueue queue = makeTwoStepQueue(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG,
                    new BigDecimal("55000"), new BigDecimal("45000"));
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willThrow(new RuntimeException("주문 거부"));

            autoTradeService.processPositionHolding(queue, state, session, "44500.00");

            ArgumentCaptor<String> linkCaptor = ArgumentCaptor.forClass(String.class);
            then(tradeFacade).should().expectExecution(eq(1L), linkCaptor.capture(), any(), any());
            then(tradeFacade).should().cancelExecution(linkCaptor.getValue(), ExchangeType.BYBIT, TradeMode.MAIN);
        }

        // ── 앱 매도/청산 주문 실패 시 이미 닫힌 포지션 처리 ──

        /** Bybit reduceOnly 주문이 포지션 0으로 거절된 상황 (retCode 110017) */
        private RuntimeException positionZeroRejection() {
            return new RuntimeException("주문 실행 실패: current position is zero, cannot fix reduce-only order qty");
        }

        @Test
        @DisplayName("매도 실패 + 거래소 포지션 0 + 거래소 익절로 닫힘: 큐를 끄지 않고 1단계로 이어간다")
        void sellFails_positionClosedByExchangeTp_resetsToFirstStep() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            state.setEntryTime(1_700_000_000_000L);
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willThrow(positionZeroRejection());
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(BigDecimal.ZERO);
            given(tradeFacade.findLastClose(1L, "BTCUSDT", 1_700_000_000_000L, ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(closeEvent(PositionCloseType.TAKE_PROFIT, ""));

            autoTradeService.processPositionHolding(queue, state, session, "55500.00");

            assertThat(queue.isActive()).isTrue();
            assertThat(state.getPhase()).isEqualTo(TradePhase.BLOCK_MATCHING);
            assertThat(state.getCurrentStepLevel()).isEqualTo(1);
            assertThat(state.getDirection()).isEqualTo(Side.LONG);
        }

        @Test
        @DisplayName("청산 실패 + 거래소 포지션 0 + 강제청산으로 닫힘: 다음 단계 반대 방향으로 이동한다")
        void liquidationFails_positionLiquidated_movesToNextStepOpposite() {
            PatternQueue queue = makeTwoStepQueue(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setEntryTime(1_700_000_000_000L);
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willThrow(positionZeroRejection());
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(BigDecimal.ZERO);
            given(tradeFacade.findLastClose(1L, "BTCUSDT", 1_700_000_000_000L, ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(closeEvent(PositionCloseType.LIQUIDATION, ""));

            autoTradeService.processPositionHolding(queue, state, session, "44500.00");

            assertThat(queue.isActive()).isTrue();
            assertThat(state.getCurrentStepLevel()).isEqualTo(2);
            assertThat(state.getDirection()).isEqualTo(Side.SHORT);
            assertThat(state.getPhase()).isEqualTo(TradePhase.BLOCK_MATCHING);
        }

        @Test
        @DisplayName("주문 실패 + 거래소 포지션 0 + 청산 건을 찾지 못함(기타): 큐를 비활성화한다")
        void closeFails_positionZero_closeTypeUnknown_deactivates() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            state.setEntryTime(1_700_000_000_000L);
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willThrow(positionZeroRejection());
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(BigDecimal.ZERO);
            given(tradeFacade.findLastClose(1L, "BTCUSDT", 1_700_000_000_000L, ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(PositionCloseDTO.builder().userId(1L).exchangeType(ExchangeType.BYBIT)
                            .symbol("BTCUSDT").closeType(PositionCloseType.OTHER).build());

            autoTradeService.processPositionHolding(queue, state, session, "55500.00");

            assertThat(queue.isActive()).isFalse();
        }

        @Test
        @DisplayName("주문 실패 + 거래소 포지션 0 + 청산 내역 조회 실패: 보유 상태를 유지한다 (30초 재조정이 재시도)")
        void closeFails_positionZero_lookupFails_keepsHolding() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            state.setEntryTime(1_700_000_000_000L);
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willThrow(positionZeroRejection());
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(BigDecimal.ZERO);
            given(tradeFacade.findLastClose(any(), any(), anyLong(), any(), any())).willReturn(null);

            autoTradeService.processPositionHolding(queue, state, session, "55500.00");

            assertThat(queue.isActive()).isTrue();
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
        }

        @Test
        @DisplayName("주문 실패 + 거래소 포지션 남아 있음 또는 조회 실패: 기존처럼 큐를 비활성화한다")
        void closeFails_positionOpenOrUnknown_deactivates() {
            PatternQueue queue1 = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state1 = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state1.setActivePatternId(100L);
            AutoTradeSessionDTO session1 = activeHoldingSession(queue1, state1);
            PatternQueue queue2 = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state2 = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state2.setActivePatternId(100L);
            AutoTradeSessionDTO session2 = makeSession(1L, "BTCUSDT", queue2);
            session2.getQueueStates().put(queue2.getId(), state2);
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willThrow(new RuntimeException("주문 거부"));
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(new BigDecimal("0.001"), (BigDecimal) null);

            autoTradeService.processPositionHolding(queue1, state1, session1, "55500.00");
            autoTradeService.processPositionHolding(queue2, state2, session2, "55500.00");

            assertThat(queue1.isActive()).isFalse();
            assertThat(queue2.isActive()).isFalse();
            then(tradeFacade).should(never()).findLastClose(any(), any(), anyLong(), any(), any());
        }

        @Test
        @DisplayName("SIM 모드 주문 실패: 거래소 확인 없이 기존처럼 큐를 비활성화한다")
        void closeFails_sim_deactivatesWithoutLookup() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            session.setTradeMode(TradeMode.SIM);
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willThrow(new RuntimeException("주문 거부"));

            autoTradeService.processPositionHolding(queue, state, session, "55500.00");

            assertThat(queue.isActive()).isFalse();
            then(tradeFacade).should(never()).findLastClose(any(), any(), anyLong(), any(), any());
        }

        @Test
        @DisplayName("큐 비활성화 알림은 세션 소유자에게만 /user/queue/autotrade.alert.{exchange}.{mode}.{symbol}로 push한다")
        void deactivate_pushesAlertToOwnerOnly() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            session.setTradeMode(TradeMode.SIM);
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willThrow(new RuntimeException("주문 거부"));

            autoTradeService.processPositionHolding(queue, state, session, "55500.00");

            ArgumentCaptor<AutoTradeAlertResponse> alert = ArgumentCaptor.forClass(AutoTradeAlertResponse.class);
            then(messagingTemplate).should().convertAndSendToUser(
                    eq("1"), eq("/queue/autotrade.alert.BYBIT.SIM.BTCUSDT"), alert.capture());
            assertThat(alert.getValue().getQueueId()).isEqualTo(1L);
            // 심볼 전체 방송은 하지 않음
            then(messagingTemplate).should(never()).convertAndSend(anyString(), any(AutoTradeAlertResponse.class));
        }

        @Test
        @DisplayName("청산 주문 응답에 orderId가 없으면 후처리에서 WS 체결 대기를 취소하고 체결 조회를 생략한다")
        void record_cancelsExpectedExecution_whenOrderIdMissing() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG,
                    new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willReturn(closeOrderResponse(null));

            autoTradeService.processPositionHolding(queue, state, session, "55500.00");

            ArgumentCaptor<String> linkCaptor = ArgumentCaptor.forClass(String.class);
            then(tradeFacade).should().expectExecution(eq(1L), linkCaptor.capture(), any(), any());
            then(tradeFacade).should().cancelExecution(linkCaptor.getValue(), ExchangeType.BYBIT, TradeMode.MAIN);
            then(tradeFacade).should(never()).getExecution(any(), any(), any(), any(), any(), any());
        }

        // ── 외부 청산 (거래소 TP/SL 발동·강제청산·수동 청산) ──

        private PositionCloseDTO closeEvent(PositionCloseType type, String orderLinkId) {
            return PositionCloseDTO.builder()
                    .userId(1L).exchangeType(ExchangeType.BYBIT).symbol("BTCUSDT")
                    .orderId("ext-1").orderLinkId(orderLinkId).execPrice("55600")
                    .closeType(type).build();
        }

        /** 보유 상태 큐를 가진 MAIN 세션을 활성 세션에 등록 */
        @SuppressWarnings("unchecked")
        private AutoTradeSessionDTO activeHoldingSession(PatternQueue queue, QueueStateDTO state) {
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            session.getQueueStates().put(queue.getId(), state);
            ((ConcurrentHashMap<String, AutoTradeSessionDTO>) ReflectionTestUtils
                    .getField(autoTradeService, "activeSessions")).put("1:BTCUSDT", session);
            return session;
        }

        @Test
        @DisplayName("외부 TP 발동: 매도와 같이 1단계로 리셋하고 실체결·실손익으로 이력을 저장한다")
        void externalTakeProfit_resetsToFirstStepAndRecords() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(BigDecimal.ZERO);

            autoTradeService.onPositionClose(closeEvent(PositionCloseType.TAKE_PROFIT, ""));

            // 상태: 1단계 같은 방향으로 블록 매칭 재시작 (주문은 보내지 않음)
            assertThat(state.getPhase()).isEqualTo(TradePhase.BLOCK_MATCHING);
            assertThat(state.getCurrentStepLevel()).isEqualTo(1);
            assertThat(state.getDirection()).isEqualTo(Side.LONG);
            assertThat(state.getEntryPrice()).isNull();
            assertThat(queue.isActive()).isTrue();
            then(tradeFacade).should(never()).placeOrder(any(), any(), any(), any(), any());
            // 이력: 외부 청산 주문 ID로 실체결·실손익 조회
            then(tradeFacade).should().getExecution(eq("ext-1"), isNull(), eq("BTCUSDT"), eq(1L), eq(ExchangeType.BYBIT), eq(TradeMode.MAIN));
            then(tradeFacade).should().getClosedPnl(argThat(q -> "ext-1".equals(q.getOrderId())
                    && "BTCUSDT".equals(q.getSymbol()) && q.getExchangeType() == ExchangeType.BYBIT), eq(1L), eq(TradeMode.MAIN));
            ArgumentCaptor<TradeHistoryDTO> historyCaptor = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should().saveTradeHistory(historyCaptor.capture(), eq(TradeMode.MAIN));
            assertThat(historyCaptor.getValue().getOrderType()).isEqualTo(TradeOrderType.SELL.getTradeOrderType());
            then(tradeFacade).should(times(1)).saveInvestmentHistory(any(), any());
            assertThat(session.getTradeLog()).anyMatch(l -> l.contains("[외부 청산]"));
        }

        @Test
        @DisplayName("외부 청산 이력의 추정 수수료는 세션 거래소의 taker 수수료율로 계산한다")
        void externalClose_feeUsesExchangeTakerRate() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            activeHoldingSession(queue, state);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(BigDecimal.ZERO);
            given(bybitTrade.getTakerFeeRate()).willReturn(new BigDecimal("0.001"));

            autoTradeService.onPositionClose(closeEvent(PositionCloseType.TAKE_PROFIT, ""));

            // 체결가 55600 × 수량 0.001 × 수수료율 0.001 = 0.0556
            ArgumentCaptor<TradeHistoryDTO> historyCaptor = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should().saveTradeHistory(historyCaptor.capture(), eq(TradeMode.MAIN));
            assertThat(historyCaptor.getValue().getFee()).isEqualByComparingTo("0.0556");
        }

        @Test
        @DisplayName("외부 SL 발동: 청산과 같이 다음 단계·반대 방향으로 이동한다")
        void externalStopLoss_movesToNextStep() {
            PatternQueue queue = makeTwoStepQueue(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            activeHoldingSession(queue, state);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(BigDecimal.ZERO);

            autoTradeService.onPositionClose(closeEvent(PositionCloseType.STOP_LOSS, ""));

            assertThat(state.getPhase()).isEqualTo(TradePhase.BLOCK_MATCHING);
            assertThat(state.getCurrentStepLevel()).isEqualTo(2);
            assertThat(state.getDirection()).isEqualTo(Side.SHORT);
            assertThat(state.getEntryMargin()).isNull();
            ArgumentCaptor<TradeHistoryDTO> historyCaptor = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should().saveTradeHistory(historyCaptor.capture(), eq(TradeMode.MAIN));
            assertThat(historyCaptor.getValue().getOrderType()).isEqualTo(TradeOrderType.LIQUIDATION.getTradeOrderType());
        }

        @Test
        @DisplayName("강제청산: 청산과 같이 다음 단계·반대 방향으로 이동한다")
        void externalLiquidation_movesToNextStep() {
            PatternQueue queue = makeTwoStepQueue(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            activeHoldingSession(queue, state);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(BigDecimal.ZERO);

            autoTradeService.onPositionClose(closeEvent(PositionCloseType.LIQUIDATION, ""));

            assertThat(state.getCurrentStepLevel()).isEqualTo(2);
            assertThat(state.getDirection()).isEqualTo(Side.SHORT);
            assertThat(state.getPhase()).isEqualTo(TradePhase.BLOCK_MATCHING);
        }

        @Test
        @DisplayName("수동 청산·ADL 등: 이력은 저장하고 큐를 비활성화한다")
        void externalOther_deactivatesQueue() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            activeHoldingSession(queue, state);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(BigDecimal.ZERO);

            autoTradeService.onPositionClose(closeEvent(PositionCloseType.OTHER, ""));

            assertThat(queue.isActive()).isFalse();
            then(patternQueueRepository).should().save(queue);
            then(tradeFacade).should(times(1)).saveInvestmentHistory(any(), any());
        }

        @Test
        @DisplayName("우리 매도/청산 주문(orderLinkId q{id}-S/L-…)의 체결은 외부 청산으로 보지 않는다")
        void ownCloseOrder_ignored() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            activeHoldingSession(queue, state);

            autoTradeService.onPositionClose(closeEvent(PositionCloseType.OTHER, "q1-S-1760000000000"));
            autoTradeService.onPositionClose(closeEvent(PositionCloseType.OTHER, "q1-L-1760000000000"));

            then(tradeFacade).should(never()).getPositionSize(any(), any(), any(), any());
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
        }

        @Test
        @DisplayName("거래소 포지션이 남아 있으면(부분 청산 등) 상태를 바꾸지 않는다")
        void positionRemains_ignored() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            activeHoldingSession(queue, state);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(new BigDecimal("0.001"));

            autoTradeService.onPositionClose(closeEvent(PositionCloseType.TAKE_PROFIT, ""));

            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
            assertThat(state.getEntryPrice()).isEqualTo("50000.00");
            then(tradeFacade).should(never()).saveTradeHistory(any(), any());
        }

        @Test
        @DisplayName("포지션 조회에 실패하면 상태를 바꾸지 않고 경고 로그를 남긴다")
        void positionCheckFails_ignoredWithWarning() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(null);

            autoTradeService.onPositionClose(closeEvent(PositionCloseType.TAKE_PROFIT, ""));

            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
            assertThat(session.getTradeLog()).anyMatch(l -> l.contains("[경고]"));
        }

        @Test
        @DisplayName("보유 중인 큐가 없으면 포지션을 조회하지 않는다")
        void noHoldingQueue_ignored() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setPhase(TradePhase.BLOCK_MATCHING);
            activeHoldingSession(queue, state);

            autoTradeService.onPositionClose(closeEvent(PositionCloseType.TAKE_PROFIT, ""));

            then(tradeFacade).should(never()).getPositionSize(any(), any(), any(), any());
        }

        @Test
        @DisplayName("SIM 세션과 다른 사용자의 세션에는 적용하지 않는다")
        void simOrOtherUserSession_ignored() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            session.setTradeMode(TradeMode.SIM);

            autoTradeService.onPositionClose(closeEvent(PositionCloseType.TAKE_PROFIT, ""));
            session.setTradeMode(TradeMode.MAIN);
            autoTradeService.onPositionClose(PositionCloseDTO.builder()
                    .userId(2L).exchangeType(ExchangeType.BYBIT).symbol("BTCUSDT").orderId("ext-2")
                    .orderLinkId("").execPrice("55600").closeType(PositionCloseType.TAKE_PROFIT).build());

            then(tradeFacade).should(never()).getPositionSize(any(), any(), any(), any());
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
        }

        // ── 익절 지정가 체결 (2-3) ──

        /** 익절 지정가(q1-T-1)가 걸린 LONG 보유 상태 */
        private QueueStateDTO tpLimitState() {
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            state.setTpOrderLinkId("q1-T-1");
            state.setTpOrderId("tp-order-1");
            state.setTpOrderPrice(new BigDecimal("55000"));
            return state;
        }

        @Test
        @DisplayName("익절 지정가 체결 이벤트: 거래소 분류(OTHER)와 무관하게 익절로 확정 → 1단계 리셋, maker 추정 수수료")
        void tpLimitFill_treatedAsTakeProfit() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitState();
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(BigDecimal.ZERO);

            autoTradeService.onPositionClose(closeEvent(PositionCloseType.OTHER, "q1-T-1"));

            assertThat(queue.isActive()).isTrue();
            assertThat(state.getPhase()).isEqualTo(TradePhase.BLOCK_MATCHING);
            assertThat(state.getCurrentStepLevel()).isEqualTo(1);
            assertThat(state.getDirection()).isEqualTo(Side.LONG);
            assertThat(state.getTpOrderLinkId()).isNull();
            then(tradeFacade).should(never()).cancelOrder(any(), any(), any(), any(), any());
            ArgumentCaptor<TradeHistoryDTO> historyCaptor = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should().saveTradeHistory(historyCaptor.capture(), eq(TradeMode.MAIN));
            assertThat(historyCaptor.getValue().getOrderType()).isEqualTo(TradeOrderType.SELL.getTradeOrderType());
            // 실체결 조회 실패 시 fallback 추정 수수료: 55600 × 0.001 × maker 0.0002 = 0.01112 → 소수 4자리 0.0111
            assertThat(historyCaptor.getValue().getFee()).isEqualByComparingTo("0.0111");
            assertThat(session.getTradeLog()).anyMatch(l -> l.contains("[익절 체결]"));
        }

        @Test
        @DisplayName("외부 청산(③): 익절 지정가가 남아 있으면 취소하고 기존 규칙대로 전환한다")
        void externalStopLoss_cancelsRemainingTpLimit() {
            PatternQueue queue = makeTwoStepQueue(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setTpOrderLinkId("q1-T-1");
            activeHoldingSession(queue, state);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(BigDecimal.ZERO);
            given(tradeFacade.cancelOrder(1L, "BTCUSDT", "q1-T-1", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(OrderCancelResult.NOT_FOUND);

            autoTradeService.onPositionClose(closeEvent(PositionCloseType.STOP_LOSS, ""));

            then(tradeFacade).should().cancelOrder(1L, "BTCUSDT", "q1-T-1", ExchangeType.BYBIT, TradeMode.MAIN);
            assertThat(state.getCurrentStepLevel()).isEqualTo(2);
            assertThat(state.getDirection()).isEqualTo(Side.SHORT);
            assertThat(state.getTpOrderLinkId()).isNull();
            assertThat(queue.isActive()).isTrue();
        }

        @Test
        @DisplayName("외부 청산(③): 남은 익절 지정가 취소 실패 시 다음 진입을 줄일 수 있으므로 큐 비활성화")
        void externalStopLoss_tpCancelFailed_deactivates() {
            PatternQueue queue = makeTwoStepQueue(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setTpOrderLinkId("q1-T-1");
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(BigDecimal.ZERO);
            given(tradeFacade.cancelOrder(any(), any(), any(), any(), any())).willReturn(OrderCancelResult.FAILED);

            autoTradeService.onPositionClose(closeEvent(PositionCloseType.STOP_LOSS, ""));

            assertThat(queue.isActive()).isFalse();
            assertThat(session.getTradeLog()).anyMatch(l -> l.contains("[큐 비활성화]") && l.contains("익절 지정가 취소 실패"));
            // 이력은 정상 저장
            then(tradeFacade).should().saveTradeHistory(any(), eq(TradeMode.MAIN));
        }

        @Test
        @DisplayName("재조정: 놓친 익절 지정가 체결도 orderLinkId로 익절 판정해 1단계로 리셋한다")
        void reconcile_missedTpLimitFill_resetsToFirstStep() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitState();
            state.setEntryTime(1_700_000_000_000L);
            activeHoldingSession(queue, state);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(BigDecimal.ZERO);
            given(tradeFacade.findLastClose(1L, "BTCUSDT", 1_700_000_000_000L, ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(closeEvent(PositionCloseType.OTHER, "q1-T-1"));

            autoTradeService.reconcileExternalCloses();

            assertThat(queue.isActive()).isTrue();
            assertThat(state.getPhase()).isEqualTo(TradePhase.BLOCK_MATCHING);
            assertThat(state.getCurrentStepLevel()).isEqualTo(1);
        }

        // ── 큐 끄기·삭제 시 보유 포지션 청산 (3-2, D4) ──

        /** 큐 종료 청산 추정 금액·SIM 체결가용 현재가 */
        private void stubTicker() {
            given(marketService.getTicker(ExchangeType.BYBIT, "BTCUSDT"))
                    .willReturn(TickerDTO.builder().lastPrice("51000").build());
        }

        /** 시장가(지정가 아님) 주문 목록 */
        private List<OrderRequestDTO> marketOrders() {
            ArgumentCaptor<OrderRequestDTO> captor = ArgumentCaptor.forClass(OrderRequestDTO.class);
            then(tradeFacade).should(atLeast(0)).placeOrder(captor.capture(), any(), any(), any(), any());
            return captor.getAllValues().stream().filter(r -> r != null && r.getPrice() == null).toList();
        }

        @Test
        @DisplayName("큐 종료 청산: 익절 지정가 취소 → 전량 시장가 청산 → 청산 이력 → 세션에서 분리 (DB는 컨트롤러가 변경)")
        void closeQueue_holding_cancelsTpAndClosesAll() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitState();
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            fakePositionSize.set(new BigDecimal("0.001"));
            given(tradeFacade.cancelOrder(1L, "BTCUSDT", "q1-T-1", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(OrderCancelResult.CANCELLED);
            given(marketService.getTicker(ExchangeType.BYBIT, "BTCUSDT"))
                    .willReturn(TickerDTO.builder().lastPrice("51000").build());

            autoTradeService.closeQueuePosition(1L, 1L);

            then(tradeFacade).should().cancelOrder(1L, "BTCUSDT", "q1-T-1", ExchangeType.BYBIT, TradeMode.MAIN);
            List<OrderRequestDTO> orders = marketOrders();
            assertThat(orders).hasSize(1);
            assertThat(orders.get(0).isReduceOnly()).isTrue();
            assertThat(orders.get(0).getQty()).isEqualTo("0.001");
            assertThat(orders.get(0).getClientOrderId()).startsWith("q1-L-");
            ArgumentCaptor<TradeHistoryDTO> historyCaptor = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should().saveTradeHistory(historyCaptor.capture(), eq(TradeMode.MAIN));
            assertThat(historyCaptor.getValue().getOrderType()).isEqualTo(TradeOrderType.LIQUIDATION.getTradeOrderType());
            then(tradeFacade).should().saveInvestmentHistory(any(), any());
            assertThat(session.getQueueStates()).doesNotContainKey(1L);
            assertThat(session.getQueues()).isEmpty();
            assertThat(queue.isActive()).isTrue();
            assertThat(session.getTradeLog()).anyMatch(l -> l.contains("[큐 종료 청산]"));
        }

        @Test
        @DisplayName("큐 종료 청산: 보유 중이 아니면 아무 주문도 내지 않는다")
        void closeQueue_notHolding_noop() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            AutoTradeSessionDTO session = activeHoldingSession(queue, QueueStateDTO.initial(1L));

            autoTradeService.closeQueuePosition(1L, 1L);

            assertThat(marketOrders()).isEmpty();
            then(tradeFacade).should(never()).cancelOrder(any(), any(), any(), any(), any());
            assertThat(session.getQueueStates()).containsKey(1L);
        }

        @Test
        @DisplayName("큐 종료 청산: 실행 중인 세션에 없는 큐(비활성)·다른 사용자의 큐는 아무것도 하지 않는다")
        void closeQueue_noSessionOrOtherUser_noop() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitState();
            activeHoldingSession(queue, state);

            autoTradeService.closeQueuePosition(1L, 999L);
            autoTradeService.closeQueuePosition(2L, 1L);

            assertThat(marketOrders()).isEmpty();
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
        }

        @Test
        @DisplayName("큐 종료 청산: 익절 지정가 취소 실패 → 청산하지 않고 예외 (큐 유지)")
        void closeQueue_tpCancelFailed_throwsAndKeeps() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitState();
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            fakePositionSize.set(new BigDecimal("0.001"));
            given(tradeFacade.cancelOrder(any(), any(), any(), any(), any())).willReturn(OrderCancelResult.FAILED);

            assertThatThrownBy(() -> autoTradeService.closeQueuePosition(1L, 1L))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getExceptionMessage())
                    .isEqualTo(ExceptionMessage.QUEUE_POSITION_CLOSE_FAILED);

            assertThat(marketOrders()).isEmpty();
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
            assertThat(state.getTpOrderLinkId()).isEqualTo("q1-T-1");
            assertThat(session.getQueueStates()).containsKey(1L);
        }

        @Test
        @DisplayName("큐 종료 청산: 익절 지정가가 이미 전량 체결됐으면 청산 생략, 익절 이력 기록 후 세션에서 분리")
        void closeQueue_tpAlreadyFilled_recordsTakeProfit() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitState();
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            fakePositionSize.set(BigDecimal.ZERO);
            given(tradeFacade.cancelOrder(any(), any(), any(), any(), any())).willReturn(OrderCancelResult.NOT_FOUND);
            given(tradeFacade.findLastClose(any(), any(), anyLong(), any(), any()))
                    .willReturn(closeEvent(PositionCloseType.OTHER, "q1-T-1"));

            autoTradeService.closeQueuePosition(1L, 1L);

            assertThat(marketOrders()).isEmpty();
            ArgumentCaptor<TradeHistoryDTO> historyCaptor = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should().saveTradeHistory(historyCaptor.capture(), eq(TradeMode.MAIN));
            assertThat(historyCaptor.getValue().getOrderType()).isEqualTo(TradeOrderType.SELL.getTradeOrderType());
            assertThat(session.getQueueStates()).doesNotContainKey(1L);
        }

        @Test
        @DisplayName("큐 종료 청산: 익절 지정가 부분 체결이면 체결분 익절 이력 + 잔여 수량만 시장가 청산")
        void closeQueue_partialTp_closesRemaining() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitState();
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            stubTicker();
            fakePositionSize.set(new BigDecimal("0.0004"));
            given(tradeFacade.cancelOrder(any(), any(), any(), any(), any())).willReturn(OrderCancelResult.CANCELLED);

            autoTradeService.closeQueuePosition(1L, 1L);

            List<OrderRequestDTO> orders = marketOrders();
            assertThat(orders).hasSize(1);
            assertThat(orders.get(0).getQty()).isEqualTo("0.0004");
            ArgumentCaptor<TradeHistoryDTO> historyCaptor = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should(times(2)).saveTradeHistory(historyCaptor.capture(), eq(TradeMode.MAIN));
            TradeHistoryDTO tpHistory = historyCaptor.getAllValues().get(0);
            assertThat(tpHistory.getOrderType()).isEqualTo(TradeOrderType.SELL.getTradeOrderType());
            assertThat(tpHistory.getExecutedPrice()).isEqualByComparingTo("55000");
            assertThat(tpHistory.getOrderId()).isEqualTo("tp-order-1");
            assertThat(session.getQueueStates()).doesNotContainKey(1L);
        }

        @Test
        @DisplayName("큐 종료 청산: 시장가 청산 주문 거부 → 예외 (큐 유지)")
        void closeQueue_closeOrderRejected_throwsAndKeeps() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            stubTicker();
            fakePositionSize.set(new BigDecimal("0.001"));
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willThrow(new RuntimeException("주문 거부"));

            assertThatThrownBy(() -> autoTradeService.closeQueuePosition(1L, 1L))
                    .isInstanceOf(CustomException.class);

            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
            assertThat(session.getQueueStates()).containsKey(1L);
        }

        @Test
        @DisplayName("큐 종료 청산: 청산 주문 후 포지션 0 미확인 → 예외, 청산 확인 대기로 신규 주문 보류 (재시도 시 확인되면 종료)")
        void closeQueue_closeNotConfirmed_throwsAndAwaitsConfirm() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            stubTicker();
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(new BigDecimal("0.001"));

            assertThatThrownBy(() -> autoTradeService.closeQueuePosition(1L, 1L))
                    .isInstanceOf(CustomException.class);
            assertThat(state.getPendingConfirmType()).isNotNull();
            assertThat(session.getQueueStates()).containsKey(1L);

            // 재시도: 이번에는 포지션 0 확인 → 추가 주문 없이 세션에서 분리
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(BigDecimal.ZERO);
            autoTradeService.closeQueuePosition(1L, 1L);

            assertThat(marketOrders()).hasSize(1);
            assertThat(session.getQueueStates()).doesNotContainKey(1L);
        }

        @Test
        @DisplayName("큐 종료 청산 미확인 후 틱에서 청산이 확인되면 다음 단계로 가지 않고 큐를 비활성화한다")
        void closeQueue_confirmedOnTick_deactivates() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            queue.setActive(true);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            stubTicker();
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(new BigDecimal("0.001"));
            assertThatThrownBy(() -> autoTradeService.closeQueuePosition(1L, 1L))
                    .isInstanceOf(CustomException.class);

            // 다음 틱: 포지션 0 확인 → 큐 비활성화 (다음 단계·재진입 없음)
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(BigDecimal.ZERO);
            state.setCloseConfirmCheckedAt(0L);
            autoTradeService.processPositionHolding(queue, state, session, "51000");

            assertThat(queue.isActive()).isFalse();
            then(patternQueueRepository).should().save(queue);
            assertThat(session.getQueueStates()).doesNotContainKey(1L);
            assertThat(marketOrders()).hasSize(1);
        }

        @Test
        @DisplayName("큐 종료 청산: SIM은 거래소 확인 없이 시장가 청산·이력 기록 후 세션에서 분리")
        void closeQueue_sim_closesWithoutExchangeCheck() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            stubTicker();
            session.setTradeMode(TradeMode.SIM);

            autoTradeService.closeQueuePosition(1L, 1L);

            assertThat(marketOrders()).hasSize(1);
            then(tradeFacade).should().saveTradeHistory(any(), eq(TradeMode.SIM));
            assertThat(session.getQueueStates()).doesNotContainKey(1L);
        }

        // ── 놓친 외부 청산 재조정 (WS 끊김 등) ──

        @Test
        @DisplayName("재조정: 보유 큐가 있는데 거래소 포지션이 0이면 진입 이후 청산 건을 찾아 방식대로 전환한다")
        void reconcile_positionClosed_appliesFoundClose() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            state.setEntryTime(1_700_000_000_000L);
            activeHoldingSession(queue, state);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(BigDecimal.ZERO);
            given(tradeFacade.findLastClose(1L, "BTCUSDT", 1_700_000_000_000L, ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(closeEvent(PositionCloseType.TAKE_PROFIT, ""));

            autoTradeService.reconcileExternalCloses();

            assertThat(state.getPhase()).isEqualTo(TradePhase.BLOCK_MATCHING);
            assertThat(state.getCurrentStepLevel()).isEqualTo(1);
            assertThat(queue.isActive()).isTrue();
            // 놓친 외부 청산도 진입 시각을 담아 손익 조회
            then(tradeFacade).should().getClosedPnl(argThat(q -> "ext-1".equals(q.getOrderId())
                    && Long.valueOf(1_700_000_000_000L).equals(q.getEntryTime())), eq(1L), eq(TradeMode.MAIN));
        }

        @Test
        @DisplayName("재조정: 청산 건을 찾지 못하면(방식 불명) 기타로 보고 큐를 비활성화한다")
        void reconcile_closeNotFound_deactivates() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            state.setEntryTime(1_700_000_000_000L);
            activeHoldingSession(queue, state);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(BigDecimal.ZERO);
            given(tradeFacade.findLastClose(1L, "BTCUSDT", 1_700_000_000_000L, ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(PositionCloseDTO.builder().userId(1L).exchangeType(ExchangeType.BYBIT)
                            .symbol("BTCUSDT").closeType(PositionCloseType.OTHER).build());

            autoTradeService.reconcileExternalCloses();

            assertThat(queue.isActive()).isFalse();
            then(tradeFacade).should(times(1)).saveInvestmentHistory(any(), any());
        }

        @Test
        @DisplayName("재조정: 거래소 포지션이 남아 있거나 조회에 실패하면 청산 내역을 찾지 않는다")
        void reconcile_positionOpenOrUnknown_skips() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            activeHoldingSession(queue, state);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(new BigDecimal("0.001"), (BigDecimal) null);

            autoTradeService.reconcileExternalCloses();
            autoTradeService.reconcileExternalCloses();

            then(tradeFacade).should(never()).findLastClose(any(), any(), anyLong(), any(), any());
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
        }

        @Test
        @DisplayName("재조정: 청산 내역 조회에 실패하면 상태를 유지한다 (다음 주기에 재시도)")
        void reconcile_lookupFails_keepsState() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setEntryTime(1_700_000_000_000L);
            activeHoldingSession(queue, state);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(BigDecimal.ZERO);
            given(tradeFacade.findLastClose(any(), any(), anyLong(), any(), any())).willReturn(null);

            autoTradeService.reconcileExternalCloses();

            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
            assertThat(queue.isActive()).isTrue();
        }

        @Test
        @DisplayName("재조정: 찾은 청산 건이 우리 매도/청산 주문이면 외부 청산으로 보지 않는다")
        void reconcile_ownCloseFound_ignored() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setEntryTime(1_700_000_000_000L);
            activeHoldingSession(queue, state);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(BigDecimal.ZERO);
            given(tradeFacade.findLastClose(any(), any(), anyLong(), any(), any()))
                    .willReturn(closeEvent(PositionCloseType.OTHER, "q1-S-1760000000000"));

            autoTradeService.reconcileExternalCloses();

            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
            then(tradeFacade).should(never()).saveTradeHistory(any(), any());
        }

        @Test
        @DisplayName("재조정: 보유 큐가 없는 세션과 SIM 세션은 포지션을 조회하지 않는다")
        void reconcile_noHoldingOrSim_skips() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            session.setTradeMode(TradeMode.SIM);
            autoTradeService.reconcileExternalCloses();

            session.setTradeMode(TradeMode.MAIN);
            state.setPhase(TradePhase.BLOCK_MATCHING);
            autoTradeService.reconcileExternalCloses();

            then(tradeFacade).should(never()).getPositionSize(any(), any(), any(), any());
        }

        @Test
        @DisplayName("같은 심볼에 보유 큐가 여러 개면 모두 전환하고, 손익은 큐별 계산식으로 저장한다")
        void multipleHoldingQueues_allHandledWithCalculatedPnl() {
            PatternQueue queue1 = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            PatternQueue queue2 = makeBothPatternQueueWithLeverage(2L, 1L, "BTCUSDT", 10);
            QueueStateDTO state1 = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            QueueStateDTO state2 = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state1.setActivePatternId(100L);
            state2.setActivePatternId(100L);
            AutoTradeSessionDTO session = activeHoldingSession(queue1, state1);
            session.getQueues().add(queue2);
            session.getQueueStates().put(queue2.getId(), state2);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(BigDecimal.ZERO);

            autoTradeService.onPositionClose(closeEvent(PositionCloseType.TAKE_PROFIT, ""));

            assertThat(state1.getPhase()).isEqualTo(TradePhase.BLOCK_MATCHING);
            assertThat(state2.getPhase()).isEqualTo(TradePhase.BLOCK_MATCHING);
            // 합쳐진 포지션의 청산 손익 1건을 큐마다 중복 저장하지 않도록 거래소 손익은 조회하지 않음
            then(tradeFacade).should(never()).getClosedPnl(any(), any(), any());
            then(tradeFacade).should(times(2)).saveInvestmentHistory(any(), any());
        }

        @Test
        @DisplayName("청산 후처리: 투자 이력 저장 실패 시 예외 로그와 함께 실패 이력(FAILED)을 거래 이력에 fallback 저장한다")
        void closeRecordFails_savesFallbackFailedHistory() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG,
                    new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willReturn(closeOrderResponse("close-1"));
            willThrow(new RuntimeException("DB down")).given(tradeFacade).saveInvestmentHistory(any(), any());

            autoTradeService.processPositionHolding(queue, state, session, "55500.00");

            // 1건: 정상 거래 이력(SUCCESS), 2건: fallback 실패 이력
            ArgumentCaptor<TradeHistoryDTO> captor = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should(times(2)).saveTradeHistory(captor.capture(), eq(TradeMode.MAIN));
            TradeHistoryDTO fallback = captor.getAllValues().get(1);
            assertThat(fallback).isNotSameAs(captor.getAllValues().get(0));
            assertThat(fallback.getOrderResult()).isEqualTo(OrderResult.FAILED);
            assertThat(fallback.getErrorMessage()).startsWith("[이력 저장 실패] 체결은 완료됨").contains("DB down");
            assertThat(fallback.getOrderType()).isEqualTo(TradeOrderType.SELL.getTradeOrderType());
            assertThat(fallback.getOrderId()).isEqualTo("close-1");
            // 매매 흐름은 계속 (1단계 리셋)
            assertThat(state.getPhase()).isEqualTo(TradePhase.BLOCK_MATCHING);
        }

        @Test
        @DisplayName("청산 후처리: fallback 저장마저 실패해도 예외가 전파되지 않는다 (에러 로그만)")
        void closeRecordFallbackAlsoFails_noPropagation() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG,
                    new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willReturn(closeOrderResponse("close-1"));
            willThrow(new RuntimeException("DB down")).given(tradeFacade).saveTradeHistory(any(), any());

            assertThatCode(() -> autoTradeService.processPositionHolding(queue, state, session, "55500.00"))
                    .doesNotThrowAnyException();
            // 정상 저장 1회 + fallback 1회 시도
            then(tradeFacade).should(times(2)).saveTradeHistory(any(), any());
            assertThat(state.getPhase()).isEqualTo(TradePhase.BLOCK_MATCHING);
        }

        @Test
        @DisplayName("청산 완료 확인: 매도 후 거래소 포지션이 남아 있으면 보유 상태로 확인 대기하고, 다음 틱에 0이 확인되면 1단계로 전환한다")
        void sell_positionRemains_waitsThenTransitionsWhenClosed() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG,
                    new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(new BigDecimal("0.001"), BigDecimal.ZERO);

            autoTradeService.processPositionHolding(queue, state, session, "55500.00");

            // 포지션 잔존 → 전환 보류
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
            assertThat(state.getPendingConfirmType()).isEqualTo(TradeOrderType.SELL);

            // 다음 틱: 신규 주문 없이 포지션만 재확인 → 0 확인 후 1단계 리셋
            state.setCloseConfirmCheckedAt(0L);
            autoTradeService.processPositionHolding(queue, state, session, "56000.00");

            then(tradeFacade).should(times(1)).placeOrder(any(), any(), any(), any(), any());
            assertThat(state.getPhase()).isEqualTo(TradePhase.BLOCK_MATCHING);
            assertThat(state.getCurrentStepLevel()).isEqualTo(1);
            assertThat(state.getPendingConfirmType()).isNull();
            assertThat(state.getCloseConfirmFailCount()).isZero();
        }

        @Test
        @DisplayName("청산 완료 확인: 손절 후 확인되면 다음 단계·반대 방향으로 이동한다")
        void liquidation_pendingThenConfirmed_movesToNextStep() {
            PatternQueue queue = makeTwoStepQueue(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG,
                    new BigDecimal("55000"), new BigDecimal("45000"));
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            // 첫 확인은 조회 실패(null), 다음 틱에 0
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(null, BigDecimal.ZERO);

            autoTradeService.processPositionHolding(queue, state, session, "44500.00");
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
            assertThat(state.getPendingConfirmType()).isEqualTo(TradeOrderType.LIQUIDATION);

            state.setCloseConfirmCheckedAt(0L);
            autoTradeService.processPositionHolding(queue, state, session, "44000.00");

            assertThat(state.getPhase()).isEqualTo(TradePhase.BLOCK_MATCHING);
            assertThat(state.getCurrentStepLevel()).isEqualTo(2);
            assertThat(state.getDirection()).isEqualTo(Side.SHORT);
        }

        @Test
        @DisplayName("청산 완료 확인: 확인 간격(1초) 이내의 틱에서는 포지션을 다시 조회하지 않는다")
        void pendingClose_throttlesRecheck() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG,
                    new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(new BigDecimal("0.001"));

            autoTradeService.processPositionHolding(queue, state, session, "55500.00");
            autoTradeService.processPositionHolding(queue, state, session, "55600.00"); // 바로 다음 틱

            then(tradeFacade).should(times(1)).getPositionSize(any(), any(), any(), any());
            assertThat(state.getCloseConfirmFailCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("청산 완료 확인: 5회 연속 확인되지 않으면 큐를 비활성화한다 (신규 주문 없음)")
        void pendingClose_fiveFailures_deactivatesQueue() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG,
                    new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            session.getQueueStates().put(1L, state);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(new BigDecimal("0.001"));

            autoTradeService.processPositionHolding(queue, state, session, "55500.00"); // 1회
            for (int i = 0; i < 4; i++) {                                               // 2~5회
                state.setCloseConfirmCheckedAt(0L);
                autoTradeService.processPositionHolding(queue, state, session, "55500.00");
            }

            then(tradeFacade).should(times(1)).placeOrder(any(), any(), any(), any(), any());
            then(tradeFacade).should(times(5)).getPositionSize(any(), any(), any(), any());
            assertThat(queue.isActive()).isFalse();
            assertThat(session.getQueueStates()).doesNotContainKey(1L);
        }

        /** 익절 지정가가 걸린 LONG 보유 상태 (진입 50000, TP 55000, SL 45000, 수량 0.001) */
        private QueueStateDTO tpLimitHoldingState() {
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setTpOrderLinkId("q1-T-1");
            state.setTpOrderId("tp-order-1");
            state.setTpOrderPrice(new BigDecimal("55000"));
            return state;
        }

        @Test
        @DisplayName("손절(2-2): 익절 지정가를 먼저 취소하고 포지션 전량을 시장가 청산 → 다음 단계")
        void stopLoss_cancelsTpThenClosesAll() {
            PatternQueue queue = makeTwoStepQueue(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitHoldingState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            fakePositionSize.set(new BigDecimal("0.001"));
            given(tradeFacade.cancelOrder(1L, "BTCUSDT", "q1-T-1", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(OrderCancelResult.CANCELLED);

            autoTradeService.processPositionHolding(queue, state, session, "44900.00");

            ArgumentCaptor<OrderRequestDTO> orders = ArgumentCaptor.forClass(OrderRequestDTO.class);
            then(tradeFacade).should().placeOrder(orders.capture(), any(), any(), any(), any());
            assertThat(orders.getValue().getQty()).isEqualTo("0.001");
            assertThat(orders.getValue().getClientOrderId()).startsWith("q1-L-");
            assertThat(state.getCurrentStepLevel()).isEqualTo(2);
            assertThat(state.getDirection()).isEqualTo(Side.SHORT);
            assertThat(state.getTpOrderLinkId()).isNull();
        }

        @Test
        @DisplayName("손절(2-2 ④): 익절 지정가 취소 실패 시 이번 틱 손절 보류, 5회 연속 실패하면 큐 비활성화")
        void stopLoss_tpCancelFailed_holdsThenDeactivates() {
            PatternQueue queue = makeTwoStepQueue(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitHoldingState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            fakePositionSize.set(new BigDecimal("0.001"));
            given(tradeFacade.cancelOrder(any(), any(), any(), any(), any())).willReturn(OrderCancelResult.FAILED);

            autoTradeService.processPositionHolding(queue, state, session, "44900.00");
            then(tradeFacade).should(never()).placeOrder(any(), any(), any(), any(), any());
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
            assertThat(queue.isActive()).isTrue();

            // 재시도 간격 이내 틱은 취소 재시도 없음
            autoTradeService.processPositionHolding(queue, state, session, "44900.00");
            then(tradeFacade).should(times(1)).cancelOrder(any(), any(), any(), any(), any());

            for (int i = 0; i < 4; i++) {
                state.setCloseConfirmCheckedAt(0L);
                autoTradeService.processPositionHolding(queue, state, session, "44900.00");
            }
            then(tradeFacade).should(never()).placeOrder(any(), any(), any(), any(), any());
            assertThat(queue.isActive()).isFalse();
        }

        @Test
        @DisplayName("손절(2-2): 취소 시 이미 체결(NOT_FOUND) + 포지션 0이면 손절 생략, 거래소 청산 내역으로 전환")
        void stopLoss_tpAlreadyFilled_skipsMarketClose() {
            PatternQueue queue = makeTwoStepQueue(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitHoldingState();
            state.setEntryTime(1_700_000_000_000L);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            fakePositionSize.set(BigDecimal.ZERO);
            given(tradeFacade.cancelOrder(any(), any(), any(), any(), any())).willReturn(OrderCancelResult.NOT_FOUND);

            autoTradeService.processPositionHolding(queue, state, session, "44900.00");

            then(tradeFacade).should(never()).placeOrder(any(), any(), any(), any(), any());
            then(tradeFacade).should().findLastClose(1L, "BTCUSDT", 1_700_000_000_000L, ExchangeType.BYBIT, TradeMode.MAIN);
        }

        @Test
        @DisplayName("손절(2-2 D1): 부분 익절 후 손절 — 합산 손익 ≥ 0이면 잔여만 시장가 청산 후 익절 경로(1단계·같은 방향)")
        void stopLoss_partialTp_positiveSum_takesProfitPath() {
            PatternQueue queue = makeTwoStepQueue(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitHoldingState();
            state.setCurrentStepLevel(2);
            state.setActivePatternId(100L);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            // 0.0009 익절 체결(+4.5), 잔여 0.0001 손절(−0.5) → 합산 양수
            fakePositionSize.set(new BigDecimal("0.0001"));
            given(tradeFacade.cancelOrder(any(), any(), any(), any(), any())).willReturn(OrderCancelResult.CANCELLED);

            autoTradeService.processPositionHolding(queue, state, session, "45000.00");

            ArgumentCaptor<OrderRequestDTO> orders = ArgumentCaptor.forClass(OrderRequestDTO.class);
            then(tradeFacade).should().placeOrder(orders.capture(), any(), any(), any(), any());
            assertThat(orders.getValue().getQty()).isEqualTo("0.0001");
            // 부분 익절분 거래 이력(SELL, 지정가 체결가) 저장
            ArgumentCaptor<TradeHistoryDTO> histories = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should(atLeast(1)).saveTradeHistory(histories.capture(), eq(TradeMode.MAIN));
            assertThat(histories.getAllValues()).anySatisfy(h -> {
                assertThat(h.getOrderType()).isEqualTo(TradeOrderType.SELL.getTradeOrderType());
                assertThat(h.getOrderId()).isEqualTo("tp-order-1");
                assertThat(h.getExecutedPrice()).isEqualByComparingTo("55000");
                assertThat(h.getOrderResult()).isEqualTo(OrderResult.SUCCESS);
            });
            assertThat(state.getCurrentStepLevel()).isEqualTo(1);
            assertThat(state.getDirection()).isEqualTo(Side.LONG);
            assertThat(state.getPhase()).isEqualTo(TradePhase.BLOCK_MATCHING);
            assertThat(session.getTradeLog()).anyMatch(l -> l.contains("부분 익절") && l.contains("익절 처리"));
        }

        @Test
        @DisplayName("손절(2-2 D1): 부분 익절 후 손절 — 합산 손익 < 0이면 손절 경로(다음 단계·반대 방향)")
        void stopLoss_partialTp_negativeSum_stopLossPath() {
            PatternQueue queue = makeTwoStepQueue(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitHoldingState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            // 0.0001 익절 체결(+0.5), 잔여 0.0009 손절(−4.5) → 합산 음수
            fakePositionSize.set(new BigDecimal("0.0009"));
            given(tradeFacade.cancelOrder(any(), any(), any(), any(), any())).willReturn(OrderCancelResult.CANCELLED);

            autoTradeService.processPositionHolding(queue, state, session, "45000.00");

            ArgumentCaptor<OrderRequestDTO> orders = ArgumentCaptor.forClass(OrderRequestDTO.class);
            then(tradeFacade).should().placeOrder(orders.capture(), any(), any(), any(), any());
            assertThat(orders.getValue().getQty()).isEqualTo("0.0009");
            assertThat(state.getCurrentStepLevel()).isEqualTo(2);
            assertThat(state.getDirection()).isEqualTo(Side.SHORT);
            assertThat(session.getTradeLog()).anyMatch(l -> l.contains("부분 익절") && l.contains("손절 처리"));
        }

        @Test
        @DisplayName("익절 지정가가 걸려 있으면 익절가에 도달해도 앱이 시장가 매도하지 않는다 (거래소 체결 이벤트 대기)")
        void tpLimitPending_skipsMarketSell() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG,
                    new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            state.setTpOrderLinkId("q1-T-1");
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            autoTradeService.processPositionHolding(queue, state, session, "55500.00");

            then(tradeFacade).should(never()).placeOrder(any(), any(), any(), any(), any());
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
        }

        @Test
        @DisplayName("청산 완료 확인: SIM은 거래소 포지션이 없으므로 조회 없이 즉시 전환한다")
        void sim_transitionsImmediatelyWithoutPositionCheck() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG,
                    new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            session.setTradeMode(TradeMode.SIM);

            autoTradeService.processPositionHolding(queue, state, session, "55500.00");

            then(tradeFacade).should(never()).getPositionSize(any(), any(), any(), any());
            assertThat(state.getPhase()).isEqualTo(TradePhase.BLOCK_MATCHING);
        }
    }

    // ─────────────────────────────────────────────
    // getStatusResponse (상태 응답 생성)
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("getStatusResponse")
    class GetStatusResponseTest {

        @Test
        @DisplayName("비활성 시 active=false를 반환한다")
        void returnsInactiveWhenNoSession() {
            var response = autoTradeService.getStatusResponse(1L, ExchangeType.BYBIT, "BTCUSDT", TradeMode.MAIN);

            assertThat(response.isActive()).isFalse();
            assertThat(response.getChangeRate()).isNull();
        }

        @Test
        @DisplayName("TRIGGER_WAIT 시 경과시간과 변동률을 반환한다")
        void returnsTriggerStateOnTriggerWait() {
            // 세션 생성
            Long userId = 1L;
            String symbol = "BTCUSDT";
            PatternQueue queue = makePatternQueue(1L, userId, symbol, Side.LONG);
            given(patternQueueRepository.findByUserIdAndSymbolAndIsActiveAndTradeModeAndExchangeTypeOrderByCreatedAtAsc(
                    userId, symbol, true, TradeMode.MAIN, ExchangeType.BYBIT)).willReturn(List.of(queue));
            autoTradeService.syncSession(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN);

            // 큐 상태에 기준가/시각 설정 (30초 전)
            var state = autoTradeService.getSession(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN).getQueueStates().get(1L);
            state.setBasePrice("50000.00");
            state.setBaseTime(LocalDateTime.now().minusSeconds(30));

            // WebSocket 현재가 모킹
            TickerDTO wsTicker = TickerDTO.builder().lastPrice("50500.00").build();
            given(marketService.getTicker(ExchangeType.BYBIT, symbol)).willReturn(wsTicker);

            var response = autoTradeService.getStatusResponse(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN);

            assertThat(response.isActive()).isTrue();
            assertThat(response.getPhase()).isEqualTo("TRIGGER_WAIT");
            assertThat(response.getElapsedSeconds()).isGreaterThanOrEqualTo(30);
            // 50000 → 50500 = +1.0%
            assertThat(response.getChangeRate()).isNotNull();
            assertThat(response.getChangeRate().doubleValue()).isCloseTo(1.0, org.assertj.core.api.Assertions.within(0.01));
            // TRIGGER_WAIT 상태에서는 투입 금액 null
            assertThat(response.getAmount()).isNull();
        }

        @Test
        @DisplayName("POSITION_HOLDING 시 진입가 대비 변동률과 TP/SL 가격을 반환한다")
        void returnsChangeRateAndTpSlOnPositionHolding() {
            Long userId = 1L;
            String symbol = "BTCUSDT";
            PatternQueue queue = makePatternQueue(1L, userId, symbol, Side.LONG);
            given(patternQueueRepository.findByUserIdAndSymbolAndIsActiveAndTradeModeAndExchangeTypeOrderByCreatedAtAsc(
                    userId, symbol, true, TradeMode.MAIN, ExchangeType.BYBIT)).willReturn(List.of(queue));
            autoTradeService.syncSession(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN);

            // POSITION_HOLDING 상태로 설정 (활성 패턴 + 진입가 + TP/SL)
            var state = autoTradeService.getSession(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN).getQueueStates().get(1L);
            state.setPhase(TradePhase.POSITION_HOLDING);
            state.setDirection(Side.LONG);
            state.setEntryPrice("50000.00");
            state.setTpPrice(new BigDecimal("55000.00"));
            state.setSlPrice(new BigDecimal("45000.00"));
            state.setActivePatternId(10L); // makePatternQueueWithLeverage에서 pattern.id = queueId * 10 = 10

            // WebSocket 현재가 모킹
            TickerDTO wsTicker = TickerDTO.builder().lastPrice("52500.00").build();
            given(marketService.getTicker(ExchangeType.BYBIT, symbol)).willReturn(wsTicker);

            var response = autoTradeService.getStatusResponse(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN);

            assertThat(response.isActive()).isTrue();
            assertThat(response.getPhase()).isEqualTo("POSITION_HOLDING");
            // 50000 → 52500 = +5.0%
            assertThat(response.getChangeRate()).isNotNull();
            assertThat(response.getChangeRate().doubleValue()).isCloseTo(5.0, org.assertj.core.api.Assertions.within(0.01));
            // 투입 금액 = 100 USDT (makePattern에서 설정)
            assertThat(response.getAmount()).isEqualByComparingTo(new BigDecimal("100"));
            // TP/SL 가격이 응답에 포함되어야 함
            assertThat(response.getTpPrice()).isEqualByComparingTo(new BigDecimal("55000.00"));
            assertThat(response.getSlPrice()).isEqualByComparingTo(new BigDecimal("45000.00"));
        }

        @Test
        @DisplayName("POSITION_HOLDING 시 진입가(entryPrice)를 반환한다")
        void returnsEntryPriceOnPositionHolding() {
            Long userId = 1L;
            String symbol = "BTCUSDT";
            PatternQueue queue = makePatternQueue(1L, userId, symbol, Side.LONG);
            given(patternQueueRepository.findByUserIdAndSymbolAndIsActiveAndTradeModeAndExchangeTypeOrderByCreatedAtAsc(
                    userId, symbol, true, TradeMode.MAIN, ExchangeType.BYBIT)).willReturn(List.of(queue));
            autoTradeService.syncSession(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN);

            var state = autoTradeService.getSession(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN).getQueueStates().get(1L);
            state.setPhase(TradePhase.POSITION_HOLDING);
            state.setDirection(Side.LONG);
            state.setEntryPrice("50000.00");
            state.setActivePatternId(10L);

            TickerDTO wsTicker = TickerDTO.builder().lastPrice("52500.00").build();
            given(marketService.getTicker(ExchangeType.BYBIT, symbol)).willReturn(wsTicker);

            var response = autoTradeService.getStatusResponse(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN);

            assertThat(response.getEntryPrice()).isEqualTo("50000.00");
        }

        @Test
        @DisplayName("익절 지정가 대기 중이면 tpOrderPending=true, 보유 큐 ID를 반환한다 (시세 없어도)")
        void returnsTpOrderPendingAndHoldingQueueIds() {
            Long userId = 1L;
            String symbol = "BTCUSDT";
            PatternQueue queue = makePatternQueue(1L, userId, symbol, Side.LONG);
            given(patternQueueRepository.findByUserIdAndSymbolAndIsActiveAndTradeModeAndExchangeTypeOrderByCreatedAtAsc(
                    userId, symbol, true, TradeMode.MAIN, ExchangeType.BYBIT)).willReturn(List.of(queue));
            autoTradeService.syncSession(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN);

            var state = autoTradeService.getSession(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN).getQueueStates().get(1L);
            state.setPhase(TradePhase.POSITION_HOLDING);
            state.setDirection(Side.LONG);
            state.setEntryPrice("50000.00");
            state.setTpOrderLinkId("q1-T-1");
            given(marketService.getTicker(ExchangeType.BYBIT, symbol)).willReturn(null);

            var response = autoTradeService.getStatusResponse(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN);

            assertThat(response.isTpOrderPending()).isTrue();
            assertThat(response.getHoldingQueueIds()).containsExactly(1L);
        }

        @Test
        @DisplayName("보유 중이 아니면 tpOrderPending=false, 보유 큐 ID는 빈 목록")
        void returnsNoHoldingWhenNotHolding() {
            Long userId = 1L;
            String symbol = "BTCUSDT";
            PatternQueue queue = makePatternQueue(1L, userId, symbol, Side.LONG);
            given(patternQueueRepository.findByUserIdAndSymbolAndIsActiveAndTradeModeAndExchangeTypeOrderByCreatedAtAsc(
                    userId, symbol, true, TradeMode.MAIN, ExchangeType.BYBIT)).willReturn(List.of(queue));
            autoTradeService.syncSession(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN);
            given(marketService.getTicker(ExchangeType.BYBIT, symbol)).willReturn(null);

            var response = autoTradeService.getStatusResponse(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN);

            assertThat(response.isTpOrderPending()).isFalse();
            assertThat(response.getHoldingQueueIds()).isEmpty();
        }

        @Test
        @DisplayName("WebSocket 가격이 없으면 변동률 null을 반환한다")
        void returnsNullChangeRateWhenNoWsPrice() {
            Long userId = 1L;
            String symbol = "BTCUSDT";
            PatternQueue queue = makePatternQueue(1L, userId, symbol, Side.LONG);
            given(patternQueueRepository.findByUserIdAndSymbolAndIsActiveAndTradeModeAndExchangeTypeOrderByCreatedAtAsc(
                    userId, symbol, true, TradeMode.MAIN, ExchangeType.BYBIT)).willReturn(List.of(queue));
            autoTradeService.syncSession(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN);

            // WebSocket 가격 없음
            given(marketService.getTicker(ExchangeType.BYBIT, symbol)).willReturn(null);

            var response = autoTradeService.getStatusResponse(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN);

            assertThat(response.isActive()).isTrue();
            assertThat(response.getChangeRate()).isNull();
            assertThat(response.getElapsedSeconds()).isEqualTo(0);
            // WebSocket 가격 없으면 투입 금액도 null
            assertThat(response.getAmount()).isNull();
        }
    }

    // ─────────────────────────────────────────────
    // STOMP 상태 push (pushAutoTradeStatus)
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("pushAutoTradeStatus: 세션 소유자에게만 /user/queue/autotrade.status.{exchange}.{mode}.{symbol}로 push한다")
    void onPriceUpdate_pushesAutoTradeStatusViaStormp() {
        // given
        Long userId = 1L;
        String symbol = "BTCUSDT";
        TradeMode tradeMode = TradeMode.MAIN;
        PatternQueue queue = makePatternQueue(1L, userId, symbol, Side.LONG);
        queue.setTriggerRate(new BigDecimal("5"));
        AutoTradeSessionDTO session = makeSession(userId, symbol, queue);
        session.setTradeMode(tradeMode);

        // 활성 세션 직접 추가
        var activeSessions = (java.util.concurrent.ConcurrentHashMap<String, AutoTradeSessionDTO>)
                ReflectionTestUtils.getField(autoTradeService, "activeSessions");
        activeSessions.put(userId + ":" + symbol + ":" + tradeMode, session);

        // WebSocket 가격 설정
        given(marketService.getTicker(ExchangeType.BYBIT, symbol))
                .willReturn(makeTickerInfo(symbol, "50000.00"));

        // when - onPriceUpdate 호출 → pushAutoTradeStatus 내부 호출
        autoTradeService.onPriceUpdate(ExchangeType.BYBIT, symbol, "50000.00");

        // then - 세션 소유자(userId)의 거래소·모드·심볼 전용 채널로만 push
        then(messagingTemplate).should(atLeastOnce())
                .convertAndSendToUser(eq("1"), eq("/queue/autotrade.status.BYBIT.MAIN.BTCUSDT"),
                        any(AutoTradeStatusResponse.class));
        // 심볼 전체 방송(다른 사용자에게 노출)은 하지 않음
        then(messagingTemplate).should(never()).convertAndSend(anyString(), any(AutoTradeStatusResponse.class));
    }

    // ─────────────────────────────────────────────
    // 헬퍼 메서드
    // ─────────────────────────────────────────────

    /**
     * 테스트용 세션 생성
     */
    // ─────────────────────────────────────────────
    // 거래량 급변 시 큐 중지
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("거래량 급변 시 큐 중지")
    class VolumeStopTest {

        private static final String RESERVED = "[거래량 급감 중지] 감지 14:03:21 · SHORT 보유 · 진입 기준 120.5 → 52.1 (-56.8%)";

        private QueueStateDTO holding(Side direction, Double baseline) {
            QueueStateDTO state = QueueStateDTO.initial(1L);
            state.setPhase(TradePhase.POSITION_HOLDING);
            state.setDirection(direction);
            state.setActivePatternId(direction == Side.LONG ? 100L : 10L);
            state.setEntryPrice("50000.00");
            state.setEntryQty("0.001");
            state.setEntryMargin(new BigDecimal("100"));
            state.setTpPrice(new BigDecimal("55000"));
            state.setSlPrice(new BigDecimal("45000"));
            state.setCurrentBlockOrder(2);
            state.setVolumeBaseline(baseline);
            return state;
        }

        private PatternQueue enabledQueue() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            queue.setVolumeStopEnabled(true);
            return queue;
        }

        private TradeHistoryDTO savedStopHistory() {
            ArgumentCaptor<TradeHistoryDTO> captor = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should(atLeastOnce()).saveTradeHistory(captor.capture(), any());
            return captor.getAllValues().stream()
                    .filter(h -> TradeOrderType.STOP.getTradeOrderType().equals(h.getOrderType()))
                    .findFirst().orElseThrow();
        }

        @Test
        @DisplayName("보유 중 거래량이 진입 기준의 0.5배 이하로 줄면 중지 예약을 DB에 저장하고 포지션은 유지한다")
        void drop_reservesStop() {
            PatternQueue queue = enabledQueue();
            QueueStateDTO state = holding(Side.LONG, 100.0);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(volumeMonitor.getVolume(ExchangeType.BYBIT, "BTCUSDT")).willReturn(40.0);

            autoTradeService.processPositionHolding(queue, state, session, "51000.00");

            ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
            then(patternQueueRepository).should().updateVolumeStopReason(eq(1L), reason.capture());
            assertThat(reason.getValue()).startsWith("[거래량 급감 중지] 감지 ")
                    .contains("LONG 보유").contains("진입 기준 100 → 40 (-60%)");
            assertThat(queue.getVolumeStopReason()).isEqualTo(reason.getValue());
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
            then(tradeFacade).should(never()).placeOrder(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("보유 중 거래량이 진입 기준의 1.5배 이상으로 늘면 급증 중지를 예약한다")
        void surge_reservesStop() {
            PatternQueue queue = enabledQueue();
            QueueStateDTO state = holding(Side.LONG, 100.0);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(volumeMonitor.getVolume(ExchangeType.BYBIT, "BTCUSDT")).willReturn(150.0);

            autoTradeService.processPositionHolding(queue, state, session, "51000.00");

            then(patternQueueRepository).should().updateVolumeStopReason(eq(1L),
                    argThat(r -> r.startsWith("[거래량 급증 중지]") && r.contains("100 → 150 (+50%)")));
        }

        @Test
        @DisplayName("0.5배 초과 ~ 1.5배 미만이면 예약하지 않는다")
        void withinRange_noReservation() {
            PatternQueue queue = enabledQueue();
            QueueStateDTO state = holding(Side.LONG, 100.0);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(volumeMonitor.getVolume(ExchangeType.BYBIT, "BTCUSDT")).willReturn(149.0);

            autoTradeService.processPositionHolding(queue, state, session, "51000.00");

            then(patternQueueRepository).should(never()).updateVolumeStopReason(any(), any());
            assertThat(queue.getVolumeStopReason()).isNull();
        }

        @Test
        @DisplayName("이미 예약된 큐와 감시를 끈 큐는 거래량을 확인하지 않는다")
        void reservedOrDisabled_skipsCheck() {
            PatternQueue reserved = enabledQueue();
            reserved.setVolumeStopReason(RESERVED);
            autoTradeService.processPositionHolding(reserved, holding(Side.LONG, 100.0),
                    makeSession(1L, "BTCUSDT", reserved), "51000.00");

            PatternQueue disabled = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            autoTradeService.processPositionHolding(disabled, holding(Side.LONG, 100.0),
                    makeSession(1L, "BTCUSDT", disabled), "51000.00");

            then(volumeMonitor).should(never()).getVolume(any(), any());
            then(patternQueueRepository).should(never()).updateVolumeStopReason(any(), any());
        }

        @Test
        @DisplayName("워밍업(B안): 진입 기준이 없으면 창이 찬 첫 시점의 거래량을 기준으로 확정하고 판정하지 않는다")
        void warmup_fixesBaselineWhenWindowFull() {
            PatternQueue queue = enabledQueue();
            QueueStateDTO state = holding(Side.LONG, null);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            // 창이 아직 안 참 → 기준 미확정
            given(volumeMonitor.getVolume(ExchangeType.BYBIT, "BTCUSDT")).willReturn(null);
            autoTradeService.processPositionHolding(queue, state, session, "51000.00");
            assertThat(state.getVolumeBaseline()).isNull();

            // 창이 참 → 그 값으로 기준 확정
            given(volumeMonitor.getVolume(ExchangeType.BYBIT, "BTCUSDT")).willReturn(80.0);
            autoTradeService.processPositionHolding(queue, state, session, "51000.00");
            assertThat(state.getVolumeBaseline()).isEqualTo(80.0);
            then(patternQueueRepository).should(never()).updateVolumeStopReason(any(), any());
        }

        @Test
        @DisplayName("진입 시 그 시점의 60초 거래량을 기준으로 저장한다")
        void entry_savesBaseline() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            queue.setVolumeStopEnabled(true);
            QueueStateDTO state = QueueStateDTO.initial(1L);
            state.setPhase(TradePhase.BLOCK_MATCHING);
            state.setDirection(Side.LONG);
            state.setActivePatternId(10L);
            state.setCurrentBlockOrder(2);
            state.setVolumeBaseline(999.0); // 이전 진입의 기준
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            given(volumeMonitor.getVolume(ExchangeType.BYBIT, "BTCUSDT")).willReturn(120.0);

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
            assertThat(state.getVolumeBaseline()).isEqualTo(120.0);
        }

        @Test
        @DisplayName("예약된 큐가 익절로 끝나면 1단계로 가지 않고 STOP 이력 저장 → 큐 비활성화 → 예약 해제")
        void reserved_afterSell_stopsQueue() {
            PatternQueue queue = enabledQueue();
            queue.setVolumeStopReason(RESERVED);
            QueueStateDTO state = holding(Side.LONG, 100.0);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            session.getQueueStates().put(1L, state);
            given(marketService.getTicker(ExchangeType.BYBIT, "BTCUSDT"))
                    .willReturn(TickerDTO.builder().symbol("BTCUSDT").lastPrice("55600").build());

            // TP 도달 → 매도
            autoTradeService.processPositionHolding(queue, state, session, "55500.00");

            TradeHistoryDTO stop = savedStopHistory();
            assertThat(stop.getOrderResult()).isEqualTo(OrderResult.SUCCESS);
            assertThat(stop.getSide()).isEqualTo(Side.LONG);
            assertThat(stop.getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(stop.getExecutedPrice()).isEqualByComparingTo(new BigDecimal("55600"));
            assertThat(stop.getPatternId()).isEqualTo(100L);
            assertThat(stop.getErrorMessage()).isEqualTo(RESERVED);
            assertThat(queue.isActive()).isFalse();
            assertThat(queue.getVolumeStopReason()).isNull();
            then(patternQueueRepository).should().save(queue);
            assertThat(session.getQueueStates()).doesNotContainKey(1L);
        }

        @Test
        @DisplayName("예약된 큐가 손절로 끝나면 다음 단계로 가지 않고 STOP 이력 저장 후 큐 비활성화")
        void reserved_afterLiquidation_stopsQueue() {
            PatternQueue queue = makeTwoStepQueue(1L, 1L, "BTCUSDT", 10);
            queue.setVolumeStopEnabled(true);
            queue.setVolumeStopReason(RESERVED);
            QueueStateDTO state = holding(Side.LONG, 100.0);
            state.setActivePatternId(10L);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            session.getQueueStates().put(1L, state);

            // SL 도달 → 청산
            autoTradeService.processPositionHolding(queue, state, session, "44500.00");

            TradeHistoryDTO stop = savedStopHistory();
            assertThat(stop.getSide()).isEqualTo(Side.LONG);
            // 현재가 조회 실패 시 0
            assertThat(stop.getExecutedPrice()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(queue.isActive()).isFalse();
            assertThat(queue.getVolumeStopReason()).isNull();
            assertThat(state.getCurrentStepLevel()).isNotEqualTo(2);
        }

        @Test
        @DisplayName("syncSession: 거래량 감시를 켠 큐의 심볼만 체결 구독 대상으로 동기화한다")
        void syncSession_syncsVolumeSubscriptions() {
            PatternQueue on = makePatternQueue(1L, 1L, "BTCUSDT", Side.LONG);
            on.setVolumeStopEnabled(true);
            PatternQueue off = makePatternQueue(2L, 1L, "ETHUSDT", Side.LONG);
            given(patternQueueRepository.findByUserIdAndSymbolAndIsActiveAndTradeModeAndExchangeTypeOrderByCreatedAtAsc(
                    1L, "BTCUSDT", true, TradeMode.MAIN, ExchangeType.BYBIT)).willReturn(List.of(on));
            given(patternQueueRepository.findByUserIdAndSymbolAndIsActiveAndTradeModeAndExchangeTypeOrderByCreatedAtAsc(
                    1L, "ETHUSDT", true, TradeMode.MAIN, ExchangeType.BYBIT)).willReturn(List.of(off));

            autoTradeService.syncSession(1L, ExchangeType.BYBIT, "BTCUSDT", TradeMode.MAIN);
            autoTradeService.syncSession(1L, ExchangeType.BYBIT, "ETHUSDT", TradeMode.MAIN);

            then(volumeMonitor).should(times(2)).syncSubscriptions(Map.of(ExchangeType.BYBIT, Set.of("BTCUSDT")));
        }
    }

    private AutoTradeSessionDTO makeSession(Long userId, String symbol, PatternQueue queue) {
        AutoTradeSessionDTO session = new AutoTradeSessionDTO();
        session.setExchangeType(ExchangeType.BYBIT);
        session.setUserId(userId);
        session.setSymbol(symbol);
        session.setQueues(new ArrayList<>(List.of(queue)));
        session.getQueueStates().put(queue.getId(), QueueStateDTO.initial(queue.getId()));
        return session;
    }

    /**
     * 테스트용 PatternQueue 생성 (1단계, 1패턴, 조건블록1개 + 리프블록1개)
     */
    private PatternQueue makePatternQueue(Long id, Long userId, String symbol, Side leafSide) {
        return makePatternQueueWithLeverage(id, userId, symbol, leafSide, 1);
    }

    /**
     * 레버리지를 지정한 PatternQueue 생성
     */
    private PatternQueue makePatternQueueWithLeverage(Long id, Long userId, String symbol,
                                                       Side leafSide, int leverage) {
        PatternQueue queue = new PatternQueue();
        queue.setExchangeType(ExchangeType.BYBIT);
        queue.setId(id);
        queue.setUserId(userId);
        queue.setSymbol(symbol);
        queue.setActive(true);

        queue.setTriggerRate(new BigDecimal("1.0"));
        queue.setFull(false);
        ReflectionTestUtils.setField(queue, "createdAt", LocalDateTime.now());

        queue.getPatterns().add(makePattern(queue, id * 10, 1, 1, leverage, leafSide, leafSide));
        return queue;
    }

    /**
     * cond 블록과 leaf 블록의 side를 다르게 지정한 PatternQueue 생성
     * (예: cond=SHORT, leaf=LONG → "S:L" 패턴)
     */
    private PatternQueue makePatternWithCondAndLeafSides(Side condSide, Side leafSide, int leverage) {
        PatternQueue queue = new PatternQueue();
        queue.setExchangeType(ExchangeType.BYBIT);
        queue.setId(1L);
        queue.setUserId(1L);
        queue.setSymbol("BTCUSDT");
        queue.setActive(true);
        queue.setTriggerRate(new BigDecimal("1.0"));
        queue.setFull(false);
        ReflectionTestUtils.setField(queue, "createdAt", LocalDateTime.now());

        queue.getPatterns().add(makePattern(queue, 10L, 1, 1, leverage, condSide, leafSide));
        return queue;
    }

    /**
     * LONG, SHORT 양쪽 패턴을 가진 PatternQueue 생성
     */
    private PatternQueue makePatternQueueWithBothPatterns(Long id, Long userId, String symbol) {
        PatternQueue queue = new PatternQueue();
        queue.setExchangeType(ExchangeType.BYBIT);
        queue.setId(id);
        queue.setUserId(userId);
        queue.setSymbol(symbol);
        queue.setActive(true);

        queue.setTriggerRate(new BigDecimal("1.0"));
        queue.setFull(false);
        ReflectionTestUtils.setField(queue, "createdAt", LocalDateTime.now());

        makeBothPatterns(1).forEach(p -> {
            p.setQueue(queue);
            queue.getPatterns().add(p);
        });
        return queue;
    }

    /**
     * 2단계 큐 생성 (1단계: LONG패턴, 2단계: LONG+SHORT 패턴)
     */
    private PatternQueue makeTwoStepQueue(Long id, Long userId, String symbol, int leverage) {
        PatternQueue queue = new PatternQueue();
        queue.setExchangeType(ExchangeType.BYBIT);
        queue.setId(id);
        queue.setUserId(userId);
        queue.setSymbol(symbol);
        queue.setActive(true);

        queue.setTriggerRate(new BigDecimal("1.0"));
        queue.setFull(false);
        ReflectionTestUtils.setField(queue, "createdAt", LocalDateTime.now());

        // 1단계: LONG 패턴
        queue.getPatterns().add(makePattern(queue, id * 10, 1, 1, leverage, Side.LONG, Side.LONG));

        // 2단계: LONG + SHORT 패턴 (패턴에 레버리지 설정)
        makeBothPatterns(2).forEach(p -> {
            p.setQueue(queue);
            p.setLeverage(leverage);
            queue.getPatterns().add(p);
        });

        return queue;
    }

    /**
     * 패턴 생성 (금액 100). blocks의 마지막 원소가 leaf 블록이다.
     */
    private Pattern makePattern(PatternQueue queue, Long id, int stepLevel, int patternOrder,
                                int leverage, Side... blocks) {
        Pattern pattern = new Pattern();
        pattern.setId(id);
        pattern.setQueue(queue);
        pattern.setStepLevel(stepLevel);
        pattern.setPatternOrder(patternOrder);
        pattern.setAmount(new BigDecimal("100"));
        pattern.setLeverage(leverage);
        pattern.setBlocks(new ArrayList<>(List.of(blocks)));
        return pattern;
    }

    /**
     * 한 단계의 LONG(id=100) + SHORT(id=200) 양쪽 패턴 생성 (큐 미지정, 레버리지 1)
     */
    private List<Pattern> makeBothPatterns(int stepLevel) {
        return List.of(
                makePattern(null, 100L, stepLevel, 1, 1, Side.LONG, Side.LONG),
                makePattern(null, 200L, stepLevel, 2, 1, Side.SHORT, Side.SHORT));
    }

    /**
     * LONG+SHORT 양쪽 패턴에 레버리지를 지정한 PatternQueue 생성
     * - Long 패턴 id=100, Short 패턴 id=200
     */
    private PatternQueue makeBothPatternQueueWithLeverage(Long id, Long userId, String symbol, int leverage) {
        PatternQueue queue = makePatternQueueWithBothPatterns(id, userId, symbol);
        // makeBothPatterns에서 leverage=1로 고정하므로 여기서 override
        queue.getPatterns().forEach(p -> p.setLeverage(leverage));
        return queue;
    }

    /**
     * 레버리지만 지정한 패턴 생성 (신호 판별 테스트용)
     */
    private Pattern makePatternWithLeverage(int leverage) {
        Pattern pattern = new Pattern();
        pattern.setId(1L);
        pattern.setLeverage(leverage);
        pattern.setAmount(new BigDecimal("100"));
        return pattern;
    }

    // ─────────────────────────────────────────────
    // QueueStateDTO.reset()
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("QueueStateDTO.reset")
    class QueueStateDTOResetTest {

        @Test
        @DisplayName("reset: 모든 상태 필드를 초기값으로 되돌린다")
        void reset_resetsAllStateFields() {
            QueueStateDTO state = QueueStateDTO.initial(1L);
            state.setPhase(TradePhase.POSITION_HOLDING);
            state.setBasePrice("50000");
            state.setDirection(Side.LONG);
            state.setCurrentStepLevel(3);
            state.setActivePatternId(88L);
            state.setCurrentBlockOrder(5);
            state.setEntryPrice("51000");
            state.setEntryQty("0.01");
            state.setEntryMargin(new BigDecimal("100"));
            state.setCloseSkipCount(2);
            state.setEntrySkipCount(3);
            state.setTpPrice(new BigDecimal("55000"));
            state.setSlPrice(new BigDecimal("48000"));

            state.reset();

            assertThat(state.getPhase()).isEqualTo(TradePhase.TRIGGER_WAIT);
            assertThat(state.getBasePrice()).isNull();
            assertThat(state.getDirection()).isNull();
            assertThat(state.getCurrentStepLevel()).isEqualTo(1);
            assertThat(state.getActivePatternId()).isNull();
            assertThat(state.getCurrentBlockOrder()).isEqualTo(1);
            assertThat(state.getEntryPrice()).isNull();
            assertThat(state.getEntryQty()).isNull();
            assertThat(state.getEntryMargin()).isNull();
            assertThat(state.getCloseSkipCount()).isEqualTo(0);
            assertThat(state.getEntrySkipCount()).isEqualTo(0);
            assertThat(state.getTpPrice()).isNull();
            assertThat(state.getSlPrice()).isNull();
        }

        @Test
        @DisplayName("reset: queueId는 reset 후에도 유지된다")
        void reset_preservesQueueId() {
            QueueStateDTO state = QueueStateDTO.initial(42L);
            state.setPhase(TradePhase.POSITION_HOLDING);
            state.setEntryPrice("50000");

            state.reset();

            assertThat(state.getQueueId()).isEqualTo(42L);
        }
    }

    private List<TickerDTO> makeTickers(String symbol, String price) {
        return List.of(makeTickerInfo(symbol, price));
    }

    /**
     * 티커 헬퍼 메서드 (STOMP 테스트용)
     */
    private TickerDTO makeTickerInfo(String symbol, String price) {
        return TickerDTO.builder()
                .symbol(symbol)
                .lastPrice(price)
                .volume24h("10000.0")
                .price24hPcnt("0.01")
                .highPrice24h("51000.0")
                .lowPrice24h("49000.0")
                .turnover24h("1000000.0")
                .build();
    }

    @Nested
    @DisplayName("hasActiveSession (거래소·모드 단위 활성 세션 확인)")
    class HasActiveSessionTest {

        @SuppressWarnings("unchecked")
        private void putSession(Long userId, ExchangeType exchangeType, TradeMode tradeMode) {
            AutoTradeSessionDTO session = new AutoTradeSessionDTO();
            session.setUserId(userId);
            session.setSymbol("BTCUSDT");
            session.setExchangeType(exchangeType);
            session.setTradeMode(tradeMode);
            ((ConcurrentHashMap<String, AutoTradeSessionDTO>) ReflectionTestUtils
                    .getField(autoTradeService, "activeSessions"))
                    .put(userId + ":" + exchangeType + ":BTCUSDT:" + tradeMode, session);
        }

        @Test
        @DisplayName("해당 사용자·거래소·모드의 세션이 하나라도 있으면 true (심볼 무관)")
        void activeSessionExists() {
            putSession(1L, ExchangeType.BYBIT, TradeMode.MAIN);

            assertThat(autoTradeService.hasActiveSession(1L, ExchangeType.BYBIT, TradeMode.MAIN)).isTrue();
            assertThat(autoTradeService.hasActiveSession(2L, ExchangeType.BYBIT, TradeMode.MAIN)).isFalse();
        }

        @Test
        @DisplayName("모의 세션만 있으면 실전 기준으로는 false")
        void onlySimSession() {
            putSession(1L, ExchangeType.BYBIT, TradeMode.SIM);

            assertThat(autoTradeService.hasActiveSession(1L, ExchangeType.BYBIT, TradeMode.MAIN)).isFalse();
        }
    }
}
