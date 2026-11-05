package com.coin.webcointrader.common.autotrade.service;

import com.coin.webcointrader.common.autotrade.entity.Pattern;
import com.coin.webcointrader.common.autotrade.entity.PatternQueue;
import com.coin.webcointrader.common.enums.Side;
import com.coin.webcointrader.common.enums.OrderCancelResult;
import com.coin.webcointrader.common.autotrade.util.TradeCalculator;
import com.coin.webcointrader.common.autotrade.dto.AutoTradeSessionDTO;
import com.coin.webcointrader.common.autotrade.dto.AutoTradeAlertResponse;
import com.coin.webcointrader.common.autotrade.dto.AutoTradeStatusResponse;
import com.coin.webcointrader.common.autotrade.dto.ExchangeProtectionGap;
import com.coin.webcointrader.common.autotrade.dto.QueueStateDTO;
import com.coin.webcointrader.common.autotrade.dto.TradePhase;
import com.coin.webcointrader.common.autotrade.repository.PatternQueueRepository;
import com.coin.webcointrader.common.trade.dto.InvestmentHistoryDTO;
import com.coin.webcointrader.common.trade.dto.TradeHistoryDTO;
import com.coin.webcointrader.common.trade.dto.ClosedPnlDTO;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.ParameterizedTest;
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
        // 거래소 보호 주문 별도 접수는 기본 성공
        given(tradeFacade.placeProtectionOrders(any(), any(), any(), any())).willReturn(true);
        // 익절 지정가 tick 반올림용 기본 tickSize
        given(marketService.getTickSize(any(), any())).willReturn("0.10");
    }

    /** 큐가 꺼질 때 보낸 ERROR 알림의 사유 */
    private String deactivateAlertReason() {
        ArgumentCaptor<AutoTradeAlertResponse> alert = ArgumentCaptor.forClass(AutoTradeAlertResponse.class);
        then(messagingTemplate).should(atLeastOnce()).convertAndSendToUser(eq("1"), anyString(), alert.capture());
        return alert.getAllValues().stream()
                .filter(a -> "ERROR".equals(a.getLevel()))
                .map(AutoTradeAlertResponse::getReason)
                .reduce((first, second) -> { throw new AssertionError("ERROR 알림이 2건 이상입니다"); })
                .orElseThrow();
    }

    /** 저장된 STOP 이력 목록 (없으면 빈 목록) */
    private List<TradeHistoryDTO> stopHistories() {
        ArgumentCaptor<TradeHistoryDTO> history = ArgumentCaptor.forClass(TradeHistoryDTO.class);
        then(tradeFacade).should(atLeast(0)).saveTradeHistory(history.capture(), any());
        return history.getAllValues().stream()
                .filter(h -> TradeOrderType.STOP.getTradeOrderType().equals(h.getOrderType()))
                .toList();
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
    // syncSession: 엔진이 끈 큐를 되살리지 않음
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("syncSession 재가동 방지")
    class SyncSessionRevivalTest {

        private final Long userId = 1L;
        private final String symbol = "BTCUSDT";

        private void stubActiveQueues(List<PatternQueue> queues) {
            given(patternQueueRepository.findByUserIdAndSymbolAndIsActiveAndTradeModeAndExchangeTypeOrderByCreatedAtAsc(
                    userId, symbol, true, TradeMode.MAIN, ExchangeType.BYBIT)).willAnswer(inv -> new ArrayList<>(queues));
        }

        private void sync() {
            autoTradeService.syncSession(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN);
        }

        private boolean sessionActive() {
            return autoTradeService.isActive(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN);
        }

        /** 세션을 만든 뒤 저장 실패 상태로 큐를 끈다 (DB는 켜짐으로 남음) */
        private PatternQueue deactivatedWithSaveFailure() {
            PatternQueue queue = makePatternQueue(1L, userId, symbol, Side.LONG);
            stubActiveQueues(List.of(queue));
            sync();
            AutoTradeSessionDTO session = autoTradeService.getSession(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN);
            given(patternQueueRepository.save(any())).willThrow(new RuntimeException("DB 장애"));
            ReflectionTestUtils.invokeMethod(autoTradeService, "deactivateQueue",
                    queue, session.getQueueStates().get(queue.getId()), session, "테스트 사유");
            assertThat(sessionActive()).isFalse();
            return queue;
        }

        @Test
        @DisplayName("세션 없음: 켜짐을 읽은 뒤 등록 전에 끄기 요청이 끝나도 꺼진 큐의 세션을 남기지 않는다")
        void syncSession_turnedOffBeforeRegister_noSessionLeft() {
            PatternQueue queue = makePatternQueue(1L, userId, symbol, Side.LONG);
            // 첫 조회는 켜짐(읽은 직후 다른 요청이 끔), 등록 후 재확인은 꺼짐
            given(patternQueueRepository.findByUserIdAndSymbolAndIsActiveAndTradeModeAndExchangeTypeOrderByCreatedAtAsc(
                    userId, symbol, true, TradeMode.MAIN, ExchangeType.BYBIT))
                    .willReturn(new ArrayList<>(List.of(queue)), new ArrayList<>());

            sync();

            assertThat(sessionActive()).isFalse();
        }

        @Test
        @DisplayName("세션 없음: 등록 후 재확인의 DB 조회가 실패하면 세션을 남기지 않고 오류를 전달한다")
        void syncSession_recheckFails_sessionRemoved() {
            PatternQueue queue = makePatternQueue(1L, userId, symbol, Side.LONG);
            given(patternQueueRepository.findByUserIdAndSymbolAndIsActiveAndTradeModeAndExchangeTypeOrderByCreatedAtAsc(
                    userId, symbol, true, TradeMode.MAIN, ExchangeType.BYBIT))
                    .willReturn(new ArrayList<>(List.of(queue)))
                    .willThrow(new RuntimeException("DB 오류"));

            assertThatThrownBy(this::sync).isInstanceOf(RuntimeException.class);

            assertThat(sessionActive()).isFalse();
        }

        @Test
        @DisplayName("세션 없음: 등록 후 재확인에서도 켜져 있으면 세션과 초기 상태를 유지한다")
        void syncSession_stillOnAfterRegister_sessionKept() {
            PatternQueue queue = makePatternQueue(1L, userId, symbol, Side.LONG);
            stubActiveQueues(List.of(queue));

            sync();

            AutoTradeSessionDTO session = autoTradeService.getSession(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN);
            assertThat(session.getQueues()).extracting(PatternQueue::getId).containsExactly(1L);
            assertThat(session.getQueueStates().get(1L).getPhase()).isEqualTo(TradePhase.TRIGGER_WAIT);
        }

        @Test
        @DisplayName("동기화가 활성 목록을 읽는 사이 레인이 큐를 꺼도 큐를 다시 등록하지 않는다")
        void syncSession_laneDeactivatesDuringSync_notRevived() throws Exception {
            ExecutorService lane = Executors.newSingleThreadExecutor();
            ExecutorService caller = Executors.newSingleThreadExecutor();
            ReflectionTestUtils.setField(autoTradeService, "sessionLaneExecutor", lane);
            try {
                PatternQueue queue = makePatternQueue(1L, userId, symbol, Side.LONG);
                java.util.concurrent.atomic.AtomicBoolean dbActive = new java.util.concurrent.atomic.AtomicBoolean(true);
                java.util.concurrent.atomic.AtomicBoolean offLaneRead = new java.util.concurrent.atomic.AtomicBoolean(false);
                java.util.concurrent.atomic.AtomicReference<Thread> callerThread = new java.util.concurrent.atomic.AtomicReference<>();
                CountDownLatch gateEntered = new CountDownLatch(1);
                CountDownLatch gate = new CountDownLatch(1);
                CountDownLatch deactivated = new CountDownLatch(1);
                given(patternQueueRepository.findByUserIdAndSymbolAndIsActiveAndTradeModeAndExchangeTypeOrderByCreatedAtAsc(
                        userId, symbol, true, TradeMode.MAIN, ExchangeType.BYBIT)).willAnswer(inv -> {
                    List<PatternQueue> snapshot = dbActive.get() ? new ArrayList<>(List.of(queue)) : new ArrayList<>();
                    // 레인 밖에서 켜짐을 읽은 경우: 읽은 값을 돌려주기 전에 레인이 큐를 끄도록 해 경합을 재현
                    if (dbActive.get() && Thread.currentThread() == callerThread.get()) {
                        offLaneRead.set(true);
                        deactivated.await(5, TimeUnit.SECONDS);
                    }
                    return snapshot;
                });
                given(patternQueueRepository.save(any())).willAnswer(inv -> {
                    dbActive.set(false);
                    return inv.getArgument(0);
                });
                sync();
                AutoTradeSessionDTO session = autoTradeService.getSession(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN);
                QueueStateDTO state = session.getQueueStates().get(queue.getId());

                // 레인을 멈춰 두고 "큐 끄기"를 먼저 대기시킨다
                session.getPendingCommands().add(() -> {
                    gateEntered.countDown();
                    try {
                        gate.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                session.getPendingCommands().add(() -> {
                    ReflectionTestUtils.invokeMethod(autoTradeService, "deactivateQueue", queue, state, session, "테스트 사유");
                    deactivated.countDown();
                });
                ReflectionTestUtils.invokeMethod(autoTradeService, "scheduleLane", session);
                assertThat(gateEntered.await(5, TimeUnit.SECONDS)).isTrue();

                java.util.concurrent.Future<?> syncing = caller.submit(() -> {
                    callerThread.set(Thread.currentThread());
                    sync();
                });
                // 동기화가 레인 밖에서 읽었거나, 레인 대기열에 들어갈 때까지 기다린 뒤 레인을 푼다
                long deadline = System.currentTimeMillis() + 5_000L;
                // (레인이 멈춰 있어 대기열에는 큐 끄기 1건이 있음 → 2건이면 동기화 명령이 들어온 것)
                while (!offLaneRead.get() && session.getPendingCommands().size() < 2
                        && System.currentTimeMillis() < deadline) {
                    Thread.sleep(5);
                }
                gate.countDown();
                syncing.get(5, TimeUnit.SECONDS);

                assertThat(deactivated.getCount()).isZero();
                assertThat(sessionActive()).isFalse();
            } finally {
                lane.shutdownNow();
                caller.shutdownNow();
            }
        }

        @Test
        @DisplayName("보유 중인 세션을 다시 동기화해도 큐 상태를 유지한다")
        void syncSession_holdingSession_keepsState() {
            PatternQueue queue = makePatternQueue(1L, userId, symbol, Side.LONG);
            stubActiveQueues(List.of(queue));
            sync();
            AutoTradeSessionDTO session = autoTradeService.getSession(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN);
            QueueStateDTO state = session.getQueueStates().get(queue.getId());
            state.setPhase(TradePhase.POSITION_HOLDING);

            sync();

            assertThat(session.getQueueStates().get(queue.getId())).isSameAs(state);
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
        }

        @Test
        @DisplayName("저장 실패로 꺼진 큐: 동기화해도 재가동하지 않고 저장을 다시 시도한다")
        void syncSession_unsavedDeactivation_notRevived_retriesSave() {
            PatternQueue queue = deactivatedWithSaveFailure();
            willReturn(queue).given(patternQueueRepository).save(any());

            sync();

            assertThat(sessionActive()).isFalse();
            assertThat(queue.isActive()).isFalse();
            then(patternQueueRepository).should(times(2)).save(queue);
        }

        @Test
        @DisplayName("저장 실패로 꺼진 큐: 재저장도 실패하면 재가동하지 않고 다음 동기화에서 다시 시도한다")
        void syncSession_unsavedDeactivation_saveFailsAgain_retriesNextSync() {
            PatternQueue queue = deactivatedWithSaveFailure();

            sync();
            sync();

            assertThat(sessionActive()).isFalse();
            then(patternQueueRepository).should(times(3)).save(queue);
        }

        @Test
        @DisplayName("재저장 성공 뒤 사용자가 다시 켜면 일반 켜기로 동작한다")
        void syncSession_afterRetrySaved_userTurnsOn_createsSession() {
            PatternQueue queue = deactivatedWithSaveFailure();
            willReturn(queue).given(patternQueueRepository).save(any());
            sync();

            queue.setActive(true);
            sync();

            assertThat(sessionActive()).isTrue();
        }

        @Test
        @DisplayName("DB가 이미 꺼진 큐는 저장 실패 기억을 지워, 이후 사용자 켜기를 막지 않는다")
        void syncSession_dbAlreadyOff_clearsUnsaved() {
            PatternQueue queue = deactivatedWithSaveFailure();
            stubActiveQueues(List.of());
            sync();

            stubActiveQueues(List.of(queue));
            queue.setActive(true);
            sync();

            assertThat(sessionActive()).isTrue();
        }
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
        @DisplayName("레인 명령이 세션을 제거하면 같은 회차의 가격을 처리하지 않는다")
        void commandRemovesSession_samePriceNotProcessed() {
            PatternQueue queue = makePatternQueue(1L, 1L, "BTCUSDT", Side.LONG);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            activeSessions().put("1:BTCUSDT", session);
            session.getPendingCommands().add(() -> activeSessions().remove("1:BTCUSDT"));

            autoTradeService.onPriceUpdate(ExchangeType.BYBIT, "BTCUSDT", "100");

            assertThat(session.getQueueStates().get(1L).getBasePrice()).isNull();
        }

        @Test
        @DisplayName("이미 종료된 세션의 큐를 비워도 같은 키의 새 세션은 제거하지 않는다")
        void deadSessionEmptied_newSessionKept() {
            for (String method : List.of("detachQueue", "deactivateQueue")) {
                PatternQueue queue = makePatternQueue(1L, 1L, "BTCUSDT", Side.LONG);
                AutoTradeSessionDTO dead = makeSession(1L, "BTCUSDT", queue);
                AutoTradeSessionDTO fresh = makeSession(1L, "BTCUSDT", makePatternQueue(2L, 1L, "BTCUSDT", Side.LONG));
                String key = ReflectionTestUtils.invokeMethod(autoTradeService, "sessionKey",
                        dead.getUserId(), dead.getExchangeType(), dead.getSymbol(), dead.getTradeMode());
                activeSessions().put(key, fresh);

                if ("detachQueue".equals(method)) {
                    ReflectionTestUtils.invokeMethod(autoTradeService, method, dead, 1L);
                } else {
                    ReflectionTestUtils.invokeMethod(autoTradeService, method, queue, dead.getQueueStates().get(1L), dead, "테스트 사유");
                }

                assertThat(activeSessions().get(key)).as(method).isSameAs(fresh);
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
        @DisplayName("이중 진입 방지: 진입가가 이미 있으면 주문하지 않고 상태를 유지한다")
        void entryPriceAlreadySet_skipsDuplicateEntry() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            state.setEntryPrice("49000.00"); // 앞선 진입이 선점한 진입가
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            // 가드가 없으면 실제 주문까지 도달하도록 수량과 포지션 조회를 정상 값으로 둔다
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(BigDecimal.ZERO);

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            then(tradeFacade).should(never()).placeOrder(any(), any(), any(), any(), any());
            assertThat(state.getEntryPrice()).isEqualTo("49000.00");
            assertThat(state.getPhase()).isEqualTo(TradePhase.BLOCK_MATCHING);
            assertThat(queue.isActive()).isTrue();
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

        // ── 주문 후 예외: 거래소에 포지션이 있을 수 있으므로 포지션 조회로 판단한다 ──

        /** 진입 시장가 주문을 체결시켜 거래소 포지션을 만들고 주문 ID를 돌려주도록 준비 */
        private void entryFills() {
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willAnswer(inv -> {
                OrderRequestDTO req = inv.getArgument(0);
                if (req.getPrice() == null && !req.isReduceOnly()) fakePositionSize.set(new BigDecimal(req.getQty()));
                return OrderResultDTO.builder().orderId("entry-1").build();
            });
        }

        /** 진입 시장가 주문이 체결됐지만 응답은 예외로 끝나도록 준비 (타임아웃 등) */
        private void entryFillsButResponseLost() {
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willAnswer(inv -> {
                OrderRequestDTO req = inv.getArgument(0);
                if (req.getPrice() != null || req.isReduceOnly()) return OrderResultDTO.builder().orderId("tp-1").build();
                fakePositionSize.set(new BigDecimal(req.getQty()));
                throw new RuntimeException("Read timed out");
            });
        }

        /** 진입 전 확인은 0, 주문 뒤 확인은 조회 실패(null)로 준비 */
        private void positionLookupFailsAfterOrder() {
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(BigDecimal.ZERO).willReturn(null);
        }

        @Test
        @DisplayName("체결 조회 예외 + 거래소 포지션 있음: 큐를 끄지 않고 추정값으로 보유 전환한다")
        void executionLookupFails_positionExists_holds() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFills();
            given(tradeFacade.getExecution(any(), any(), any(), any(), any(), any())).willThrow(new RuntimeException("timeout"));

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
            assertThat(state.getEntryPrice()).isEqualTo("50000.00");
            assertThat(state.getPendingConfirmType()).isNull();
            assertThat(queue.isActive()).isTrue();
            assertThat(session.getQueues()).contains(queue);
        }

        @Test
        @DisplayName("체결 조회 예외 + 포지션 조회 실패: 큐를 끄지 않고 진입 확인 대기로 둔다")
        void executionLookupFails_positionUnknown_waitsForConfirm() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFills();
            positionLookupFailsAfterOrder();
            given(tradeFacade.getExecution(any(), any(), any(), any(), any(), any())).willThrow(new RuntimeException("timeout"));

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
            assertThat(state.getPendingConfirmType()).isEqualTo(TradeOrderType.ENTRY);
            assertThat(queue.isActive()).isTrue();
            assertThat(session.getQueues()).contains(queue);
        }

        @Test
        @DisplayName("체결 조회 예외 + 거래소 포지션 0: 진입 미체결로 처리한다")
        void executionLookupFails_noPosition_failsAsUnfilled() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any()))
                    .willReturn(OrderResultDTO.builder().orderId("entry-1").build());
            given(tradeFacade.getExecution(any(), any(), any(), any(), any(), any())).willThrow(new RuntimeException("timeout"));

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            assertThat(state.getEntryPrice()).isNull();
            assertThat(queue.isActive()).isFalse();
            assertThat(session.getTradeLog()).anyMatch(l -> l.contains("진입 미체결"));
        }

        @Test
        @DisplayName("주문 결과 불명확 예외 + 거래소 포지션 있음: 큐를 끄지 않고 추정값으로 보유 전환한다")
        void ambiguousOrderFailure_positionExists_holds() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFillsButResponseLost();

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
            assertThat(state.getEntryPrice()).isEqualTo("50000.00");
            assertThat(queue.isActive()).isTrue();
            assertThat(session.getQueues()).contains(queue);
            then(tradeFacade).should(never()).getExecution(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("주문 결과 불명확 예외 + 포지션 조회 실패: 큐를 끄지 않고 진입 확인 대기로 둔다")
        void ambiguousOrderFailure_positionUnknown_waitsForConfirm() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willThrow(new RuntimeException("Read timed out"));
            positionLookupFailsAfterOrder();

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            assertThat(state.getPendingConfirmType()).isEqualTo(TradeOrderType.ENTRY);
            assertThat(queue.isActive()).isTrue();
            assertThat(session.getQueues()).contains(queue);
        }

        @Test
        @DisplayName("주문 거부 확정(CustomException): 포지션을 다시 조회하지 않고 주문 실패로 큐를 끈다")
        void rejectedOrder_failsWithoutPositionCheck() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any()))
                    .willThrow(new CustomException(ExceptionMessage.ORDER_FAILED, "[110007] insufficient"));

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            // 진입 전 확인 1회뿐 — 주문 뒤 재조회 없음
            then(tradeFacade).should(times(1)).getPositionSize(any(), any(), any(), any());
            assertThat(state.getEntryPrice()).isNull();
            assertThat(queue.isActive()).isFalse();
            assertThat(session.getTradeLog()).anyMatch(l -> l.contains("주문 실패"));
            // 거부 이력은 TradeFacade가 저장하므로 엔진이 중복 저장하지 않는다
            then(tradeFacade).should(never()).saveTradeHistory(any(), any());
        }

        @Test
        @DisplayName("모의 모드 주문 예외: 포지션 조회 없이 주문 실패로 큐를 끈다")
        void simOrderFailure_failsWithoutPositionCheck() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            session.setTradeMode(TradeMode.SIM);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willThrow(new RuntimeException("잔고 부족"));

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            then(tradeFacade).should(never()).getPositionSize(any(), any(), any(), any());
            assertThat(state.getEntryPrice()).isNull();
            assertThat(queue.isActive()).isFalse();
            assertThat(session.getTradeLog()).anyMatch(l -> l.contains("주문 실패"));
        }

        @Test
        @DisplayName("주문 접수 뒤 처리 예외(체결값 파싱 오류): 큐를 끄고 거래소 포지션 확인을 안내한다")
        void failureAfterOrderAccepted_alertsManualCheck() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFills();
            // 진입 전 확인은 0, 주문 뒤 포지션 판단 단계에서 예상 못 한 예외
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(BigDecimal.ZERO).willThrow(new IllegalStateException("처리 오류"));

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            assertThat(state.getEntryPrice()).isNull();
            assertThat(queue.isActive()).isFalse();
            assertThat(session.getTradeLog()).anyMatch(l -> l.contains("거래소 포지션을 확인"));
            assertThat(session.getTradeLog()).noneMatch(l -> l.contains("주문 실패"));
            // 체결됐을 수 있는 주문이므로 주문 ID와 수동 확인 안내가 담긴 이력을 1건 남긴다
            ArgumentCaptor<TradeHistoryDTO> captor = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should(times(1)).saveTradeHistory(captor.capture(), eq(TradeMode.MAIN));
            assertThat(captor.getValue().getOrderResult()).isEqualTo(OrderResult.FAILED);
            assertThat(captor.getValue().getOrderId()).isEqualTo("entry-1");
            assertThat(captor.getValue().getExecutedPrice()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(captor.getValue().getErrorMessage()).contains("거래소 포지션을 확인", "처리 오류");
        }

        @Test
        @DisplayName("접수 뒤 이력 저장까지 끝난 다음의 예외: 큐는 끄되 이력을 중복 저장하지 않는다")
        void failureAfterHistorySaved_noDuplicateHistory() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            queue.setVolumeStopEnabled(true);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFills();
            // 이력 저장 뒤 거래량 기준값을 잡을 때 예외
            given(marketService.getTicker(any(), any())).willThrow(new IllegalStateException("시세 오류"));

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            assertThat(queue.isActive()).isFalse();
            ArgumentCaptor<TradeHistoryDTO> captor = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should(times(1)).saveTradeHistory(captor.capture(), eq(TradeMode.MAIN));
            assertThat(captor.getValue().getOrderResult()).isEqualTo(OrderResult.SUCCESS);
        }

        @Test
        @DisplayName("체결값 비정상 + 거래소 포지션 있음: 체결 정보 없음으로 보고 추정값으로 보유 전환한다")
        void invalidExecutionValues_positionExists_holds() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFills();
            given(tradeFacade.getExecution(any(), any(), any(), any(), any(), any()))
                    .willReturn(ExecutionDTO.builder().execPrice("50010").execFee("숫자아님").build());

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
            assertThat(state.getEntryPrice()).isEqualTo("50000.00");
            assertThat(queue.isActive()).isTrue();
        }

        @Test
        @DisplayName("체결가 0: 체결 정보 없음으로 보고 추정값으로 보유 전환한다")
        void zeroExecutionPrice_positionExists_holds() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFills();
            given(tradeFacade.getExecution(any(), any(), any(), any(), any(), any()))
                    .willReturn(ExecutionDTO.builder().execPrice("0").execFee("0.1").build());

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            assertThat(state.getEntryPrice()).isEqualTo("50000.00");
            assertThat(queue.isActive()).isTrue();
        }

        // ── 체결 정보 없음: 거래소 포지션 크기로 진입 수량을 확인한다 ──

        /** 진입 시장가 주문 뒤 거래소 포지션이 주어진 크기가 되도록 준비 (체결 정보 조회는 기본값 null) */
        private void entryFillsWithPositionSize(String size) {
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willAnswer(inv -> {
                OrderRequestDTO req = inv.getArgument(0);
                if (req.getPrice() == null && !req.isReduceOnly()) fakePositionSize.set(new BigDecimal(size));
                else if (req.getPrice() == null) fakePositionSize.set(BigDecimal.ZERO);
                return OrderResultDTO.builder().orderId(req.getPrice() != null ? "tp-1" : "entry-1").build();
            });
        }

        /** 확인 대기 재확인 1회 (1초 간격 제한을 지나도록 시각을 되돌린다) */
        private void recheckPendingEntry(PatternQueue queue, QueueStateDTO state, AutoTradeSessionDTO session) {
            state.setCloseConfirmCheckedAt(0L);
            autoTradeService.processPositionHolding(queue, state, session, "50000.00");
        }

        @Test
        @DisplayName("체결 정보 없음 + 포지션이 요청과 같음: 요청 수량으로 즉시 보유 전환한다")
        void noExecution_positionEqualsRequested_holds() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFillsWithPositionSize("0.0020");

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            assertThat(state.getPendingConfirmType()).isNull();
            assertThat(state.getEntryQty()).isEqualTo("0.002");
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
        }

        @Test
        @DisplayName("체결 정보 없음 + 포지션이 요청보다 작음: 확인 대기 뒤 같아지면 요청 수량으로 확정한다")
        void noExecution_smallerPosition_waitsThenConfirmsRequested() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFillsWithPositionSize("0.001");

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            // 반영 지연과 일부 체결을 구분할 수 없으므로 이력 저장과 익절 지정가를 보류
            assertThat(state.getPendingConfirmType()).isEqualTo(TradeOrderType.ENTRY);
            then(tradeFacade).should(never()).saveTradeHistory(any(), any());
            BigDecimal margin = state.getEntryMargin();

            fakePositionSize.set(new BigDecimal("0.002"));
            recheckPendingEntry(queue, state, session);

            assertThat(state.getPendingConfirmType()).isNull();
            assertThat(state.getEntryQty()).isEqualTo("0.002");
            assertThat(state.getEntryMargin()).isEqualByComparingTo(margin);
            then(tradeFacade).should(times(1)).saveTradeHistory(any(), eq(TradeMode.MAIN));
        }

        @Test
        @DisplayName("체결 정보 없음 + 5회째에도 작음: 실제 크기로 확정하고 마진·수수료·이력 금액을 비례 축소한다")
        void noExecution_stillSmallerAtLastCheck_shrinksToActualSize() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFillsWithPositionSize("0.001");

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");
            BigDecimal margin = state.getEntryMargin();
            BigDecimal fee = state.getEntryFee();

            // 첫 판단이 1회째이므로 재확인 3회까지는 대기
            for (int i = 0; i < 3; i++) recheckPendingEntry(queue, state, session);
            assertThat(state.getPendingConfirmType()).isEqualTo(TradeOrderType.ENTRY);
            assertThat(state.getEntryQty()).isEqualTo("0.002");

            recheckPendingEntry(queue, state, session);

            assertThat(state.getPendingConfirmType()).isNull();
            assertThat(state.getEntryQty()).isEqualTo("0.001");
            assertThat(state.getEntryMargin()).isEqualByComparingTo(margin.divide(BigDecimal.valueOf(2)));
            assertThat(state.getEntryFee()).isEqualByComparingTo(fee.divide(BigDecimal.valueOf(2)));
            assertThat(queue.isActive()).isTrue();

            ArgumentCaptor<TradeHistoryDTO> history = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should(times(1)).saveTradeHistory(history.capture(), eq(TradeMode.MAIN));
            assertThat(history.getValue().getOrderResult()).isEqualTo(OrderResult.SUCCESS);
            assertThat(history.getValue().getAmount()).isEqualByComparingTo(margin.divide(BigDecimal.valueOf(2)));
            assertThat(history.getValue().getFee()).isEqualByComparingTo(fee.divide(BigDecimal.valueOf(2)));

            // 익절 지정가도 실제 크기로 등록
            ArgumentCaptor<OrderRequestDTO> orders = ArgumentCaptor.forClass(OrderRequestDTO.class);
            then(tradeFacade).should(atLeastOnce()).placeOrder(orders.capture(), any(), any(), any(), any());
            assertThat(orders.getAllValues()).filteredOn(o -> o.getPrice() != null)
                    .singleElement().satisfies(o -> assertThat(o.getQty()).isEqualTo("0.001"));
        }

        @Test
        @DisplayName("실제 크기로 축소한 뒤 손절: 없던 부분 익절로 판단하지 않는다")
        void noExecution_shrunkThenStopLoss_noPartialTakeProfit() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            given(tradeFacade.cancelOrder(any(), any(), any(), any(), any())).willReturn(OrderCancelResult.CANCELLED);
            entryFillsWithPositionSize("0.001");

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");
            for (int i = 0; i < 4; i++) recheckPendingEntry(queue, state, session);

            autoTradeService.processPositionHolding(queue, state, session, "45000.00");

            assertThat(session.getTradeLog()).noneMatch(l -> l.contains("부분 익절"));
        }

        @Test
        @DisplayName("체결 정보 없음 + 포지션이 요청보다 큼: 요청 수량을 유지하고 경고 알림을 보낸다")
        void noExecution_largerPosition_keepsRequestedAndWarns() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFillsWithPositionSize("0.003");

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            assertThat(state.getEntryQty()).isEqualTo("0.002");
            assertThat(state.getPendingConfirmType()).isNull();
            assertThat(queue.isActive()).isTrue();
            ArgumentCaptor<AutoTradeAlertResponse> alert = ArgumentCaptor.forClass(AutoTradeAlertResponse.class);
            then(messagingTemplate).should().convertAndSendToUser(eq("1"), anyString(), alert.capture());
            assertThat(alert.getValue().getLevel()).isEqualTo("WARN");
            assertThat(alert.getValue().getReason()).contains("0.003", "0.002");
        }

        @Test
        @DisplayName("확인 대기 중 포지션이 요청보다 크게 확인됨: 요청 수량을 유지하고 경고 알림을 보낸다")
        void pendingEntry_largerPosition_keepsRequestedAndWarns() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFills();
            given(tradeFacade.getExecution(any(), any(), any(), any(), any(), any())).willReturn(null);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(BigDecimal.ZERO).willReturn(null).willReturn(new BigDecimal("0.003"));

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");
            recheckPendingEntry(queue, state, session);

            assertThat(state.getPendingConfirmType()).isNull();
            assertThat(state.getEntryQty()).isEqualTo("0.002");
            ArgumentCaptor<AutoTradeAlertResponse> alert = ArgumentCaptor.forClass(AutoTradeAlertResponse.class);
            then(messagingTemplate).should().convertAndSendToUser(eq("1"), anyString(), alert.capture());
            assertThat(alert.getValue().getLevel()).isEqualTo("WARN");
        }

        // ── 확인 대기 횟수: 조회 실패와 수량 부족은 따로 센다 ──

        @Test
        @DisplayName("작음 4회 뒤 조회 실패 1회: 큐를 끄지 않고 다음 작음에서 실제 크기로 확정한다")
        void pendingEntry_shortFourTimesThenLookupFails_keepsWaitingThenShrinks() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFills();
            given(tradeFacade.getExecution(any(), any(), any(), any(), any(), any())).willReturn(null);
            BigDecimal small = new BigDecimal("0.001");
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(BigDecimal.ZERO).willReturn(small).willReturn(small).willReturn(small).willReturn(small)
                    .willReturn(null).willReturn(small);

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");
            for (int i = 0; i < 3; i++) recheckPendingEntry(queue, state, session);

            // 조회 실패 1회는 수량 부족 횟수와 합산되지 않는다
            recheckPendingEntry(queue, state, session);
            assertThat(queue.isActive()).isTrue();
            assertThat(state.getPendingConfirmType()).isEqualTo(TradeOrderType.ENTRY);
            assertThat(state.getEntryQty()).isEqualTo("0.002");

            recheckPendingEntry(queue, state, session);
            assertThat(state.getPendingConfirmType()).isNull();
            assertThat(state.getEntryQty()).isEqualTo("0.001");
            assertThat(queue.isActive()).isTrue();
            ArgumentCaptor<TradeHistoryDTO> history = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should(times(1)).saveTradeHistory(history.capture(), eq(TradeMode.MAIN));
            assertThat(history.getValue().getOrderResult()).isEqualTo(OrderResult.SUCCESS);
        }

        @Test
        @DisplayName("조회 실패 4회 뒤 첫 작음: 바로 축소하지 않고 대기하며, 같아지면 요청 수량으로 확정한다")
        void pendingEntry_lookupFailsFourTimesThenShort_keepsWaiting() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFills();
            given(tradeFacade.getExecution(any(), any(), any(), any(), any(), any())).willReturn(null);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(BigDecimal.ZERO).willReturn(null).willReturn(null).willReturn(null).willReturn(null)
                    .willReturn(new BigDecimal("0.001")).willReturn(new BigDecimal("0.002"));

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");
            for (int i = 0; i < 3; i++) recheckPendingEntry(queue, state, session);

            recheckPendingEntry(queue, state, session);
            assertThat(state.getPendingConfirmType()).isEqualTo(TradeOrderType.ENTRY);
            assertThat(state.getEntryQty()).isEqualTo("0.002");

            recheckPendingEntry(queue, state, session);
            assertThat(state.getPendingConfirmType()).isNull();
            assertThat(state.getEntryQty()).isEqualTo("0.002");
        }

        @Test
        @DisplayName("조회 실패와 작음이 번갈아 나옴: 작음 5회째에 실제 크기로 확정한다")
        void pendingEntry_lookupFailAndShortAlternate_shrinksAtFifthShort() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFills();
            given(tradeFacade.getExecution(any(), any(), any(), any(), any(), any())).willReturn(null);
            BigDecimal small = new BigDecimal("0.001");
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(BigDecimal.ZERO)
                    .willReturn(null).willReturn(small).willReturn(null).willReturn(small).willReturn(null)
                    .willReturn(small).willReturn(null).willReturn(small).willReturn(null).willReturn(small);

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");
            for (int i = 0; i < 8; i++) recheckPendingEntry(queue, state, session);
            assertThat(queue.isActive()).isTrue();
            assertThat(state.getPendingConfirmType()).isEqualTo(TradeOrderType.ENTRY);

            recheckPendingEntry(queue, state, session);
            assertThat(state.getPendingConfirmType()).isNull();
            assertThat(state.getEntryQty()).isEqualTo("0.001");
            assertThat(queue.isActive()).isTrue();
        }

        // ── 거래소 보호 주문: 주문 결과가 없으면 진입 주문 처리에서 걸지 못했으므로 다시 건다 ──

        @Test
        @DisplayName("주문 결과 없음 + 포지션 있음: 진입 요청으로 보호 주문을 1회 건다")
        void responseLost_positionExists_placesProtectionOnce() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFillsButResponseLost();

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            ArgumentCaptor<OrderRequestDTO> request = ArgumentCaptor.forClass(OrderRequestDTO.class);
            then(tradeFacade).should(times(1)).placeProtectionOrders(request.capture(), eq(1L), eq(ExchangeType.BYBIT), eq(TradeMode.MAIN));
            assertThat(request.getValue().isReduceOnly()).isFalse();
            assertThat(request.getValue().getClientOrderId()).startsWith("q1-E-");
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
        }

        @Test
        @DisplayName("주문 결과 있음 + 체결 조회만 실패: 이미 걸었으므로 보호 주문을 다시 걸지 않는다")
        void executionLookupFails_doesNotPlaceProtectionAgain() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFills();
            given(tradeFacade.getExecution(any(), any(), any(), any(), any(), any())).willThrow(new RuntimeException("timeout"));

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            then(tradeFacade).should(never()).placeProtectionOrders(any(), any(), any(), any());
            then(messagingTemplate).should(never()).convertAndSendToUser(anyString(), anyString(), any(AutoTradeAlertResponse.class));
        }

        @Test
        @DisplayName("주문 결과 없음 + 포지션 조회 실패: 포지션을 처음 확인할 때 보호 주문을 1회만 건다")
        void responseLost_pending_placesProtectionOnceOnFirstConfirm() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFillsButResponseLost();
            // 진입 전 0 → 조회 실패 → 작게 확인(보호 주문 설치, 대기 유지) → 같아짐(재설치 없음)
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(BigDecimal.ZERO).willReturn(null)
                    .willReturn(new BigDecimal("0.001")).willReturn(new BigDecimal("0.002"));

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");
            // 포지션 확인 전에는 걸지 않는다
            then(tradeFacade).should(never()).placeProtectionOrders(any(), any(), any(), any());
            assertThat(state.getPendingConfirmType()).isEqualTo(TradeOrderType.ENTRY);

            recheckPendingEntry(queue, state, session);
            then(tradeFacade).should(times(1)).placeProtectionOrders(any(), any(), any(), any());
            assertThat(state.getPendingConfirmType()).isEqualTo(TradeOrderType.ENTRY);

            recheckPendingEntry(queue, state, session);
            then(tradeFacade).should(times(1)).placeProtectionOrders(any(), any(), any(), any());
            assertThat(state.getPendingConfirmType()).isNull();
        }

        @Test
        @DisplayName("주문 결과 없음 + 포지션이 요청보다 작음: 확인 대기 중에도 보호 주문은 바로 건다")
        void responseLost_smallerPosition_placesProtectionWhileWaiting() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFillsButResponseLost();
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(BigDecimal.ZERO).willReturn(new BigDecimal("0.001")).willReturn(new BigDecimal("0.002"));

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");
            assertThat(state.getPendingConfirmType()).isEqualTo(TradeOrderType.ENTRY);
            then(tradeFacade).should(times(1)).placeProtectionOrders(any(), any(), any(), any());

            recheckPendingEntry(queue, state, session);
            then(tradeFacade).should(times(1)).placeProtectionOrders(any(), any(), any(), any());
        }

        @Test
        @DisplayName("주문 결과 없음 + 포지션 0: 보호 주문을 걸지 않는다")
        void responseLost_noPosition_doesNotPlaceProtection() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willThrow(new RuntimeException("Read timed out"));

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            then(tradeFacade).should(never()).placeProtectionOrders(any(), any(), any(), any());
        }

        @Test
        @DisplayName("보호 주문 설치 실패: 보유·큐를 유지하고 경고 알림을 보낸다")
        void protectionInstallFails_keepsPositionAndWarns() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFillsButResponseLost();
            given(tradeFacade.placeProtectionOrders(any(), any(), any(), any())).willReturn(false);

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            assertProtectionFailureWarned(queue, state, session);
        }

        @Test
        @DisplayName("보호 주문 설치 중 예외: 진입 실패로 번지지 않고 경고 알림을 보낸다")
        void protectionInstallThrows_keepsPositionAndWarns() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFillsButResponseLost();
            given(tradeFacade.placeProtectionOrders(any(), any(), any(), any())).willThrow(new RuntimeException("키 조회 실패"));

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            assertProtectionFailureWarned(queue, state, session);
        }

        @Test
        @DisplayName("주문 결과의 보호 주문 실패 표시: 보유·큐를 유지하고 경고 알림을 보낸다")
        void orderResultProtectionFailed_keepsPositionAndWarns() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willAnswer(inv -> {
                OrderRequestDTO req = inv.getArgument(0);
                if (req.getPrice() == null && !req.isReduceOnly()) fakePositionSize.set(new BigDecimal(req.getQty()));
                return OrderResultDTO.builder().orderId("entry-1").protectionFailed(true).build();
            });

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            assertProtectionFailureWarned(queue, state, session);
            then(tradeFacade).should(never()).placeProtectionOrders(any(), any(), any(), any());
        }

        // ── 진입 때 보호 주문 실패 뒤 큐가 꺼지는 경로의 안내 ──

        /** 큐 꺼짐 사유·이력 앞에 붙는 문구 */
        private static final String NO_PROTECTION_NOTE = "[거래소 안전망 미설치 — 앱의 익절·손절도 멈춥니다] ";

        /** 진입 시장가는 체결되지만 거래소 보호 주문은 실패한 결과를 돌려주도록 준비 */
        private void entryFillsWithProtectionFailed() {
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willAnswer(inv -> {
                OrderRequestDTO req = inv.getArgument(0);
                if (req.getPrice() == null && !req.isReduceOnly()) fakePositionSize.set(new BigDecimal(req.getQty()));
                return OrderResultDTO.builder().orderId("entry-1").protectionFailed(true).build();
            });
        }

        /** 보유 중 청산 수량 오류 5회로 큐를 끈다 (포지션 상태와 무관하게 꺼지는 경로) */
        private void deactivateWhileHolding(PatternQueue queue, QueueStateDTO state, AutoTradeSessionDTO session) {
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
            assertThat(state.getPendingConfirmType()).isNull();
            state.setEntryQty(null);
            state.setTpOrderLinkId(null);
            state.setTpPrice(new BigDecimal("55000"));
            state.setSlPrice(new BigDecimal("45000"));
            state.setCloseSkipCount(4);
            autoTradeService.processPositionHolding(queue, state, session, "55500.00");
            assertThat(queue.isActive()).isFalse();
        }

        /** 마지막으로 저장한 이력의 오류 문구 */
        private String lastHistoryErrorMessage() {
            ArgumentCaptor<TradeHistoryDTO> history = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should(atLeastOnce()).saveTradeHistory(history.capture(), eq(TradeMode.MAIN));
            return history.getValue().getErrorMessage();
        }

        @Test
        @DisplayName("진입 때 보호 실패 뒤 보유 중 큐가 꺼지면: 큐 꺼짐 사유 앞에 앱 감시도 멈춘다고 알린다")
        void protectionFailed_deactivatedWhileHolding_disclosesInReason() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFillsWithProtectionFailed();

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");
            deactivateWhileHolding(queue, state, session);

            assertThat(deactivateAlertReason()).startsWith(NO_PROTECTION_NOTE).contains("청산 수량 오류 5회 초과");
        }

        @Test
        @DisplayName("진입 때 보호 실패 + 포지션 조회 5회 실패: 큐 꺼짐 사유와 이력 앞에 알린다")
        void protectionFailed_lookupFailsFiveTimes_disclosesInReasonAndHistory() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFillsWithProtectionFailed();

            failPositionLookupFiveTimes(queue, state, session);

            assertThat(queue.isActive()).isFalse();
            assertThat(deactivateAlertReason()).startsWith(NO_PROTECTION_NOTE);
            assertThat(lastHistoryErrorMessage()).startsWith(NO_PROTECTION_NOTE);
        }

        @Test
        @DisplayName("보호 실패 결과 뒤 포지션 조회 예외: 경고 없이 꺼지지 않고 큐 꺼짐 사유와 이력에 알린다")
        void protectionFailed_positionLookupThrows_disclosesInReasonAndHistory() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFillsWithProtectionFailed();
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(BigDecimal.ZERO).willThrow(new RuntimeException("timeout"));

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            assertThat(queue.isActive()).isFalse();
            assertThat(deactivateAlertReason()).startsWith(NO_PROTECTION_NOTE);
            assertThat(lastHistoryErrorMessage()).startsWith(NO_PROTECTION_NOTE);
        }

        @Test
        @DisplayName("응답 유실 뒤 재확인 때 보호 주문 실패 → 보유 중 큐 꺼짐: 사유 앞에 알린다")
        void protectionFailsOnRecheck_deactivatedWhileHolding_disclosesInReason() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFillsButResponseLost();
            given(tradeFacade.placeProtectionOrders(any(), any(), any(), any())).willReturn(false);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(BigDecimal.ZERO).willReturn(null).willReturn(new BigDecimal("0.002"));

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");
            recheckPendingEntry(queue, state, session);
            deactivateWhileHolding(queue, state, session);

            assertThat(deactivateAlertReason()).startsWith(NO_PROTECTION_NOTE);
        }

        @Test
        @DisplayName("보호 실패 뒤 청산이 확인되면: 포지션이 없으므로 이후 큐 꺼짐에 문구를 붙이지 않는다")
        void protectionFailed_closeConfirmed_noNote() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFillsWithProtectionFailed();
            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            // 큐 종료 청산이 접수되어 확인 대기 중이고, 거래소 포지션은 0
            state.setStopRequested(true);
            state.setPendingConfirmType(TradeOrderType.SELL);
            state.setCloseConfirmCheckedAt(0L);
            fakePositionSize.set(BigDecimal.ZERO);
            autoTradeService.processPositionHolding(queue, state, session, "50000.00");

            assertThat(queue.isActive()).isFalse();
            assertThat(deactivateAlertReason()).doesNotContain("거래소 안전망").contains("포지션 청산 확인");
        }

        @Test
        @DisplayName("보호 실패 뒤 외부 청산으로 포지션이 닫혀 큐가 꺼지면: 문구를 붙이지 않는다")
        @SuppressWarnings("unchecked")
        void protectionFailed_externalClose_noNote() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFillsWithProtectionFailed();
            given(tradeFacade.cancelOrder(any(), any(), any(), any(), any())).willReturn(OrderCancelResult.CANCELLED);
            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            session.getQueueStates().put(queue.getId(), state);
            ((ConcurrentHashMap<String, AutoTradeSessionDTO>) ReflectionTestUtils
                    .getField(autoTradeService, "activeSessions")).put("1:BTCUSDT", session);
            fakePositionSize.set(BigDecimal.ZERO);
            autoTradeService.onPositionClose(PositionCloseDTO.builder()
                    .userId(1L).exchangeType(ExchangeType.BYBIT).symbol("BTCUSDT")
                    .orderId("ext-1").orderLinkId("").execPrice("50000")
                    .closeType(PositionCloseType.OTHER).build());

            assertThat(queue.isActive()).isFalse();
            assertThat(deactivateAlertReason()).doesNotContain("거래소 안전망").contains("외부 청산");
        }

        @Test
        @DisplayName("보호 실패 뒤 익절 등록 실패로 청산해 포지션 0이 확인되면: 문구를 붙이지 않는다")
        void protectionFailed_tpLimitFails_closedPosition_noNote() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willAnswer(inv -> {
                OrderRequestDTO req = inv.getArgument(0);
                if (req.getPrice() != null) throw new RuntimeException("익절 지정가 거부");
                if (req.isReduceOnly()) {
                    fakePositionSize.set(BigDecimal.ZERO);
                    return OrderResultDTO.builder().orderId("close-1").build();
                }
                fakePositionSize.set(new BigDecimal(req.getQty()));
                return OrderResultDTO.builder().orderId("entry-1").protectionFailed(true).build();
            });

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            assertThat(queue.isActive()).isFalse();
            assertThat(deactivateAlertReason()).doesNotContain("거래소 안전망").contains("포지션 청산 완료");
        }

        @Test
        @DisplayName("보호 실패 결과 + 주문 후 포지션 0(진입 미체결): 포지션이 없으므로 문구를 붙이지 않는다")
        void protectionFailed_unfilled_noNote() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            // 거래소 포지션은 계속 0 (fakePositionSize를 갱신하지 않음)
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willAnswer(inv ->
                    OrderResultDTO.builder().orderId("entry-1").protectionFailed(true).build());

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            assertThat(queue.isActive()).isFalse();
            assertThat(deactivateAlertReason()).doesNotContain("거래소 안전망").contains("진입 미체결");
        }

        @Test
        @DisplayName("이전 표시가 남아 있어도 보호 성공 진입 뒤 큐가 꺼지면: 문구를 붙이지 않는다")
        void staleMark_nextEntrySucceeds_noNote() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            state.setProtectionGap(ExchangeProtectionGap.ALL);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFills();

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");
            deactivateWhileHolding(queue, state, session);

            assertThat(deactivateAlertReason()).doesNotContain("거래소 안전망");
        }

        @Test
        @DisplayName("보호 성공 진입 뒤 큐가 꺼지면: 문구를 붙이지 않는다")
        void protectionSucceeded_deactivatedWhileHolding_noNote() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFills();

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");
            deactivateWhileHolding(queue, state, session);

            assertThat(deactivateAlertReason()).doesNotContain("거래소 안전망");
        }

        // ── 거래소 TP/SL을 처음부터 생략한 진입 뒤 큐가 꺼지는 경로의 안내 ──

        @Test
        @DisplayName("거래소 SL만 생략한 진입 뒤 보유 중 큐가 꺼지면: 사유 앞에 손절 미설치를 알린다")
        void slOmitted_deactivatedWhileHolding_disclosesStopLoss() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            setRates(queue, null, "9"); // 10배 한도 8.5% 초과 → 거래소 SL 생략
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFills();

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");
            deactivateWhileHolding(queue, state, session);

            assertThat(capturedEntryOrder().getStopLoss()).isNull();
            assertThat(capturedEntryOrder().getTakeProfit()).isNotNull();
            assertThat(deactivateAlertReason()).startsWith("[거래소 손절 미설치 — 앱의 익절·손절도 멈춥니다] ");
        }

        @Test
        @DisplayName("거래소 TP만 생략한 진입 뒤 보유 중 큐가 꺼지면: 사유 앞에 익절 미설치를 알린다")
        void tpOmitted_deactivatedWhileHolding_disclosesTakeProfit() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.SHORT, 10);
            setRates(queue, "99.7", null); // SHORT 거래소 TP는 앱보다 0.5%p 바깥(100.2%) → 0 이하라 생략
            QueueStateDTO state = leafReadyState();
            state.setDirection(Side.SHORT);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFills();

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");
            deactivateWhileHolding(queue, state, session);

            assertThat(capturedEntryOrder().getTakeProfit()).isNull();
            assertThat(capturedEntryOrder().getStopLoss()).isNotNull();
            assertThat(deactivateAlertReason()).startsWith("[거래소 익절 미설치 — 앱의 익절·손절도 멈춥니다] ");
        }

        @Test
        @DisplayName("거래소 TP/SL을 둘 다 생략한 진입 뒤 보유 중 큐가 꺼지면: 사유 앞에 안전망 미설치를 알린다")
        void bothOmitted_deactivatedWhileHolding_disclosesAll() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            given(marketService.getTickSize(ExchangeType.BYBIT, "BTCUSDT")).willReturn(null, "0.10"); // 진입 시 안전망 계산만 실패
            entryFills();

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");
            deactivateWhileHolding(queue, state, session);

            assertThat(deactivateAlertReason()).startsWith(NO_PROTECTION_NOTE);
        }

        @Test
        @DisplayName("SL 생략 + 나머지 보호 주문 접수 실패: 어느 쪽이 실패했는지 모르므로 안전망 미설치로 알린다")
        void slOmitted_protectionFailed_disclosesAll() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            setRates(queue, null, "9");
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFillsWithProtectionFailed();

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");
            deactivateWhileHolding(queue, state, session);

            assertThat(deactivateAlertReason()).startsWith(NO_PROTECTION_NOTE);
        }

        @Test
        @DisplayName("SL 생략 + 진입 주문 거부: 포지션이 없으므로 문구를 붙이지 않는다")
        void slOmitted_entryRejected_noNote() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            setRates(queue, null, "9");
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willThrow(new CustomException(ExceptionMessage.ORDER_FAILED, "진입 거부"));

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            assertThat(state.getProtectionGap()).isEqualTo(ExchangeProtectionGap.NONE);
            assertThat(queue.isActive()).isFalse();
            assertThat(deactivateAlertReason()).doesNotContain("미설치");
        }

        @Test
        @DisplayName("SIM에서 SL 생략 뒤 큐가 꺼지면: 거래소 보호 주문이 원래 없으므로 문구를 붙이지 않는다")
        void sim_slOmitted_deactivated_noNote() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            setRates(queue, null, "9");
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            session.setTradeMode(TradeMode.SIM);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");
            deactivateWhileHolding(queue, state, session);

            assertThat(deactivateAlertReason()).doesNotContain("미설치");
            assertThat(stopHistories()).isEmpty();
        }

        @Test
        @DisplayName("안전망 취소 표시가 남아 있어도 다음 진입 뒤 큐가 꺼지면: 문구를 붙이지 않는다")
        void cancelledMark_nextEntrySucceeds_noNote() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            state.setProtectionGap(ExchangeProtectionGap.CANCELLED);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFills();

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");
            deactivateWhileHolding(queue, state, session);

            assertThat(deactivateAlertReason()).doesNotContain("거래소 안전망");
            assertThat(stopHistories()).isEmpty();
        }

        @Test
        @DisplayName("보호 실패 뒤 진입 처리 예외: FAILED 이력은 사유만, STOP 이력에 문구와 실제 진입 방향을 남긴다")
        void protectionFailed_entryException_recordsFailedAndStop() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            state.setDirection(null); // 진입 처리 전이라 방향이 아직 없는 상태
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFillsWithProtectionFailed();
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(BigDecimal.ZERO).willThrow(new RuntimeException("timeout"));

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            assertThat(queue.isActive()).isFalse();
            ArgumentCaptor<TradeHistoryDTO> history = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should(times(2)).saveTradeHistory(history.capture(), eq(TradeMode.MAIN));
            TradeHistoryDTO failed = history.getAllValues().get(0);
            TradeHistoryDTO stop = history.getAllValues().get(1);
            assertThat(failed.getOrderResult()).isEqualTo(OrderResult.FAILED);
            assertThat(failed.getErrorMessage()).doesNotContain("거래소 안전망");
            assertThat(stop.getOrderType()).isEqualTo(TradeOrderType.STOP.getTradeOrderType());
            assertThat(stop.getErrorMessage()).startsWith(NO_PROTECTION_NOTE);
            assertThat(stop.getSide()).isEqualTo(Side.LONG);
        }

        @Test
        @DisplayName("익절 지정가 등록 실패 후 청산 미확인으로 큐가 꺼지면: STOP 이력에 문구를 남긴다")
        void tpRegisterFailed_closeUnconfirmed_recordsStop() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            given(marketService.getTickSize(any(), any())).willReturn(null); // TP/SL 생략 + 익절 지정가 등록 실패
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(BigDecimal.ZERO).willReturn(new BigDecimal("0.002"));

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            assertThat(queue.isActive()).isFalse();
            assertThat(stopHistories()).singleElement().satisfies(stop -> {
                assertThat(stop.getErrorMessage()).startsWith(NO_PROTECTION_NOTE);
                assertThat(stop.getErrorMessage()).contains("포지션 청산 미확인");
            });
        }

        @Test
        @DisplayName("주문 후 포지션 0(진입 미체결): 이력·사유·로그에 뒤늦은 체결 가능성과 거래소 확인을 안내한다")
        void unfilledEntry_warnsLateFill() {
            String lateFill = " — 뒤늦게 체결되면 앱 감시 없이 포지션이 남습니다. 거래소를 확인해 주세요";
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            // 거래소 포지션은 계속 0 (fakePositionSize를 갱신하지 않음)
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willAnswer(inv ->
                    OrderResultDTO.builder().orderId("entry-1").build());

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            assertThat(queue.isActive()).isFalse();
            assertThat(lastHistoryErrorMessage()).startsWith("[진입 미체결]").endsWith(lateFill);
            assertThat(deactivateAlertReason()).startsWith("진입 미체결").contains(lateFill);
            assertThat(session.getTradeLog()).anyMatch(l -> l.contains("[진입 실패]") && l.endsWith(lateFill));
        }

        // ── 큐를 끄기 전 보호 주문: 포지션을 확인 못 한 채 감시를 멈추는 경로 ──

        /** 진입 후 포지션 조회가 계속 실패하도록 준비하고, 첫 판단과 재확인 4회(총 5회 실패)까지 진행 */
        private void failPositionLookupFiveTimes(PatternQueue queue, QueueStateDTO state, AutoTradeSessionDTO session) {
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(BigDecimal.ZERO).willReturn(null);
            autoTradeService.processBlockMatching(queue, state, session, "50000.00");
            for (int i = 0; i < 4; i++) recheckPendingEntry(queue, state, session);
        }

        /** 큐를 끄는 경로의 보호 주문 실패 안내: 경고는 앱 감시 중단을 알리고, 큐 꺼짐 알림과 이력에도 실패 사실이 남는다 */
        private void assertProtectionFailureDisclosedOnDeactivate(PatternQueue queue) {
            assertThat(queue.isActive()).isFalse();
            ArgumentCaptor<AutoTradeAlertResponse> alert = ArgumentCaptor.forClass(AutoTradeAlertResponse.class);
            then(messagingTemplate).should(times(2)).convertAndSendToUser(eq("1"), anyString(), alert.capture());
            AutoTradeAlertResponse warn = alert.getAllValues().get(0);
            AutoTradeAlertResponse error = alert.getAllValues().get(1);
            assertThat(warn.getLevel()).isEqualTo("WARN");
            assertThat(warn.getReason()).doesNotContain("계속 동작").contains("앱의 익절·손절도 멈춥니다");
            assertThat(error.getLevel()).isEqualTo("ERROR");
            assertThat(error.getReason()).startsWith(NO_PROTECTION_NOTE);
            ArgumentCaptor<TradeHistoryDTO> history = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should(times(2)).saveTradeHistory(history.capture(), eq(TradeMode.MAIN));
            TradeHistoryDTO failed = history.getAllValues().get(0);
            TradeHistoryDTO stop = history.getAllValues().get(1);
            assertThat(failed.getOrderResult()).isEqualTo(OrderResult.FAILED);
            assertThat(failed.getErrorMessage()).doesNotContain("거래소 안전망");
            assertThat(stop.getOrderType()).isEqualTo(TradeOrderType.STOP.getTradeOrderType());
            assertThat(stop.getErrorMessage()).startsWith(NO_PROTECTION_NOTE);
        }

        @Test
        @DisplayName("조회 5회 실패 + 보호 주문 false: 앱 감시 중단을 알리고 사유와 이력에 실패를 남긴다")
        void lookupFailsFiveTimes_protectionFalse_disclosesNoProtection() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFillsButResponseLost();
            given(tradeFacade.placeProtectionOrders(any(), any(), any(), any())).willReturn(false);

            failPositionLookupFiveTimes(queue, state, session);

            assertProtectionFailureDisclosedOnDeactivate(queue);
        }

        @Test
        @DisplayName("조회 5회 실패 + 보호 주문 예외: 앱 감시 중단을 알리고 사유와 이력에 실패를 남긴다")
        void lookupFailsFiveTimes_protectionThrows_disclosesNoProtection() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFillsButResponseLost();
            given(tradeFacade.placeProtectionOrders(any(), any(), any(), any())).willThrow(new IllegalStateException("거래소 오류"));

            failPositionLookupFiveTimes(queue, state, session);

            assertProtectionFailureDisclosedOnDeactivate(queue);
        }

        @Test
        @DisplayName("처리 예외 경로 + 보호 주문 false: 앱 감시 중단을 알리고 사유와 이력에 실패를 남긴다")
        void laterException_protectionFalse_disclosesNoProtection() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            queue.setVolumeStopEnabled(true);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFillsButResponseLost();
            positionLookupFailsAfterOrder();
            given(marketService.getTicker(any(), any())).willThrow(new IllegalStateException("시세 오류"));
            given(tradeFacade.placeProtectionOrders(any(), any(), any(), any())).willReturn(false);

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            assertProtectionFailureDisclosedOnDeactivate(queue);
        }

        @Test
        @DisplayName("처리 예외 경로 + 보호 주문 예외: 앱 감시 중단을 알리고 사유와 이력에 실패를 남긴다")
        void laterException_protectionThrows_disclosesNoProtection() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            queue.setVolumeStopEnabled(true);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFillsButResponseLost();
            positionLookupFailsAfterOrder();
            given(marketService.getTicker(any(), any())).willThrow(new IllegalStateException("시세 오류"));
            given(tradeFacade.placeProtectionOrders(any(), any(), any(), any())).willThrow(new IllegalStateException("거래소 오류"));

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            assertProtectionFailureDisclosedOnDeactivate(queue);
        }

        @Test
        @DisplayName("보호 주문 성공 후 큐 끄기: 안전망 미설치 문구를 넣지 않는다")
        void lookupFailsFiveTimes_protectionPlaced_noFailureNote() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFillsButResponseLost();
            given(tradeFacade.placeProtectionOrders(any(), any(), any(), any())).willReturn(true);

            failPositionLookupFiveTimes(queue, state, session);

            ArgumentCaptor<AutoTradeAlertResponse> alert = ArgumentCaptor.forClass(AutoTradeAlertResponse.class);
            then(messagingTemplate).should(times(1)).convertAndSendToUser(eq("1"), anyString(), alert.capture());
            assertThat(alert.getValue().getReason()).doesNotContain("거래소 안전망 미설치");
        }

        @Test
        @DisplayName("큐를 유지하는 경로의 보호 주문 실패: 앱 익절·손절이 계속 동작한다는 경고를 유지한다")
        void keepQueue_protectionFails_keepsContinuingWarning() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFillsButResponseLost();
            given(tradeFacade.placeProtectionOrders(any(), any(), any(), any())).willReturn(false);

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            assertThat(queue.isActive()).isTrue();
            ArgumentCaptor<AutoTradeAlertResponse> alert = ArgumentCaptor.forClass(AutoTradeAlertResponse.class);
            then(messagingTemplate).should(times(1)).convertAndSendToUser(eq("1"), anyString(), alert.capture());
            assertThat(alert.getValue().getLevel()).isEqualTo("WARN");
            assertThat(alert.getValue().getReason()).contains("계속 동작");
        }

        /** 보낸 자동매매 알림의 등급 목록 */
        private List<String> alertLevels(int expectedCount) {
            ArgumentCaptor<AutoTradeAlertResponse> alert = ArgumentCaptor.forClass(AutoTradeAlertResponse.class);
            then(messagingTemplate).should(times(expectedCount)).convertAndSendToUser(eq("1"), anyString(), alert.capture());
            return alert.getAllValues().stream().map(AutoTradeAlertResponse::getLevel).toList();
        }

        @Test
        @DisplayName("주문 결과 없음 + 조회 5회 실패: 보호 주문을 1회 걸고, 체결가 0 실패 이력과 거래소 확인 안내를 남기고 큐를 끈다")
        void responseLost_lookupFailsFiveTimes_placesProtectionThenDeactivates() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFillsButResponseLost();

            failPositionLookupFiveTimes(queue, state, session);

            then(tradeFacade).should(times(1)).placeProtectionOrders(any(), eq(1L), eq(ExchangeType.BYBIT), eq(TradeMode.MAIN));
            assertThat(queue.isActive()).isFalse();
            ArgumentCaptor<TradeHistoryDTO> history = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should(times(1)).saveTradeHistory(history.capture(), eq(TradeMode.MAIN));
            assertThat(history.getValue().getOrderResult()).isEqualTo(OrderResult.FAILED);
            assertThat(history.getValue().getExecutedPrice()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(history.getValue().getErrorMessage()).contains("거래소를 확인");
            ArgumentCaptor<AutoTradeAlertResponse> alert = ArgumentCaptor.forClass(AutoTradeAlertResponse.class);
            then(messagingTemplate).should().convertAndSendToUser(eq("1"), anyString(), alert.capture());
            assertThat(alert.getValue().getLevel()).isEqualTo("ERROR");
            assertThat(alert.getValue().getReason()).contains("거래소를 확인");
        }

        @Test
        @DisplayName("주문 결과 없음 + 조회 5회 실패 + 보호 주문 실패: 경고와 큐 꺼짐 알림을 모두 보낸다")
        void responseLost_lookupFailsFiveTimes_protectionFails_alertsBoth() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFillsButResponseLost();
            given(tradeFacade.placeProtectionOrders(any(), any(), any(), any())).willReturn(false);

            failPositionLookupFiveTimes(queue, state, session);

            assertThat(queue.isActive()).isFalse();
            assertThat(alertLevels(2)).containsExactly("WARN", "ERROR");
        }

        @Test
        @DisplayName("주문 결과 있음 + 조회 5회 실패: 이미 걸었으므로 보호 주문을 다시 걸지 않고 큐를 끈다")
        void orderResultExists_lookupFailsFiveTimes_doesNotPlaceProtection() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFills();
            given(tradeFacade.getExecution(any(), any(), any(), any(), any(), any())).willReturn(null);

            failPositionLookupFiveTimes(queue, state, session);

            then(tradeFacade).should(never()).placeProtectionOrders(any(), any(), any(), any());
            assertThat(queue.isActive()).isFalse();
        }

        @Test
        @DisplayName("주문 결과 없음 + 포지션 조회 예외: 큐를 끄기 전에 보호 주문을 1회 건다")
        void responseLost_positionLookupThrows_placesProtectionBeforeDeactivating() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFillsButResponseLost();
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(BigDecimal.ZERO).willThrow(new IllegalStateException("처리 오류"));

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            then(tradeFacade).should(times(1)).placeProtectionOrders(any(), any(), any(), any());
            assertThat(queue.isActive()).isFalse();
            assertThat(session.getTradeLog()).anyMatch(l -> l.contains("거래소 포지션을 확인"));
        }

        @Test
        @DisplayName("주문 결과 없음 + 조회 실패로 대기 진입 후 처리 예외: 큐를 끄기 전에 보호 주문을 1회 건다")
        void responseLost_lookupFails_laterException_placesProtectionBeforeDeactivating() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            queue.setVolumeStopEnabled(true);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFillsButResponseLost();
            positionLookupFailsAfterOrder();
            given(marketService.getTicker(any(), any())).willThrow(new IllegalStateException("시세 오류"));

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            then(tradeFacade).should(times(1)).placeProtectionOrders(any(), any(), any(), any());
            assertThat(queue.isActive()).isFalse();
        }

        @Test
        @DisplayName("주문 결과 없음 + 보호 주문 설치 뒤 처리 예외: 다시 걸지 않는다")
        void responseLost_protectionPlaced_laterException_doesNotPlaceAgain() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            queue.setVolumeStopEnabled(true);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");
            entryFillsButResponseLost();
            given(marketService.getTicker(any(), any())).willThrow(new IllegalStateException("시세 오류"));

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            then(tradeFacade).should(times(1)).placeProtectionOrders(any(), any(), any(), any());
            assertThat(queue.isActive()).isFalse();
        }

        /** 보호 주문 실패 정책 검증: 포지션과 큐를 유지하고 로그와 WARN 알림으로 알린다 */
        private void assertProtectionFailureWarned(PatternQueue queue, QueueStateDTO state, AutoTradeSessionDTO session) {
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
            assertThat(state.getEntryPrice()).isNotNull();
            assertThat(queue.isActive()).isTrue();
            assertThat(session.getTradeLog()).anyMatch(l -> l.contains("거래소 안전망"));
            ArgumentCaptor<AutoTradeAlertResponse> alert = ArgumentCaptor.forClass(AutoTradeAlertResponse.class);
            then(messagingTemplate).should().convertAndSendToUser(eq("1"), anyString(), alert.capture());
            assertThat(alert.getValue().getLevel()).isEqualTo("WARN");
            assertThat(alert.getValue().getReason()).contains("거래소 안전망");
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

        // ── 가격 기준 TP/SL + 거래소 안전망 TP/SL ──

        /** 큐의 첫 패턴에 익절/손절률(가격 변동 기준 %) 설정 */
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
        @DisplayName("[사용자 정책 2026-10-31] 익절/손절률은 가격 변동 기준 — 10배, 익절 5% / 손절 3% → 가격 +5% / -3%")
        void tpSlRatesArePriceBased() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            setRates(queue, "5", "3");
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
            // 10배 한도 가격 8.5% → 손절 9%는 초과
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            setRates(queue, null, "9");
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
        @DisplayName("매도: 투자 이력의 익절/손절 예상가도 가격 변동 기준으로 계산한다")
        void sell_expectedPricesArePriceBased() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            // 10배, 익절 가격 5% / 손절 가격 3% → 진입가 50000 기준 52500 / 48500
            queue.getPatterns().forEach(p -> {
                p.setTakeProfitRate(new BigDecimal("5"));
                p.setStopLossRate(new BigDecimal("3"));
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
        @DisplayName("주문 실패 + 거래소 포지션 0 + 청산 내역 없음: 기록·전환을 보류하고 다음 틱에 주문을 반복하지 않는다")
        void closeFails_positionZero_closeDetailMissing_holds() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            state.setEntryTime(1_700_000_000_000L);
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willThrow(positionZeroRejection());
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(BigDecimal.ZERO);
            given(tradeFacade.findLastClose(1L, "BTCUSDT", 1_700_000_000_000L, ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(closeWithoutDetail());

            autoTradeService.processPositionHolding(queue, state, session, "55500.00");
            autoTradeService.processPositionHolding(queue, state, session, "55500.00");

            assertThat(queue.isActive()).isTrue();
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
            assertThat(state.getCloseDetailWaitStartedAt()).isNotNull();
            then(tradeFacade).should(times(1)).placeOrder(any(), any(), any(), any(), any());
            then(tradeFacade).should(never()).saveTradeHistory(any(), any());
            then(tradeFacade).should(never()).saveInvestmentHistory(any(), any());
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
        @DisplayName("큐 비활성화: DB 저장이 실패해도 세션에서 제거·알림하고 다음 가격에 다시 주문하지 않는다")
        void deactivate_saveFails_detachesAndAlerts() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            session.setTradeMode(TradeMode.SIM);
            // 빈 세션 정리까지 검증하도록 실제 세션 키 형식으로 다시 등록
            @SuppressWarnings("unchecked")
            ConcurrentHashMap<String, AutoTradeSessionDTO> active = (ConcurrentHashMap<String, AutoTradeSessionDTO>)
                    ReflectionTestUtils.getField(autoTradeService, "activeSessions");
            active.clear();
            active.put("1:BYBIT:BTCUSDT:SIM", session);
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willThrow(new RuntimeException("주문 거부"));
            given(patternQueueRepository.save(any())).willThrow(new RuntimeException("DB 장애"));

            autoTradeService.onPriceUpdate(ExchangeType.BYBIT, "BTCUSDT", "55500.00");
            // 비활성화된 큐는 다음 가격에 처리되지 않아야 한다
            autoTradeService.onPriceUpdate(ExchangeType.BYBIT, "BTCUSDT", "55600.00");

            then(tradeFacade).should(times(1)).placeOrder(any(), any(), any(), any(), any());
            assertThat(session.getQueues()).isEmpty();
            assertThat(session.getQueueStates()).doesNotContainKey(1L);
            ArgumentCaptor<AutoTradeAlertResponse> alert = ArgumentCaptor.forClass(AutoTradeAlertResponse.class);
            then(messagingTemplate).should().convertAndSendToUser(
                    eq("1"), eq("/queue/autotrade.alert.BYBIT.SIM.BTCUSDT"), alert.capture());
            assertThat(alert.getValue().getReason()).contains("DB 저장 실패");
            assertThat(active).doesNotContainValue(session);
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
        @DisplayName("큐 종료 청산: 보유 중이 아니면 주문 없이 세션에서 분리한다")
        void closeQueue_notHolding_detachesWithoutOrder() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            AutoTradeSessionDTO session = activeHoldingSession(queue, QueueStateDTO.initial(1L));

            autoTradeService.closeQueuePosition(1L, 1L);

            assertThat(marketOrders()).isEmpty();
            then(tradeFacade).should(never()).cancelOrder(any(), any(), any(), any(), any());
            assertThat(session.getQueueStates()).doesNotContainKey(1L);
            assertThat(session.getQueues()).isEmpty();
        }

        @Test
        @DisplayName("큐 끄기 경합: leaf 대기 큐를 끈 뒤 DB 반영 전에 가격이 와도 진입하지 않는다")
        void closeQueue_leafReady_noEntryOnNextPrice() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = QueueStateDTO.initial(1L);
            state.setPhase(TradePhase.BLOCK_MATCHING);
            state.setDirection(Side.LONG);
            state.setActivePatternId(10L);
            state.setCurrentBlockOrder(2); // leaf 위치 → 다음 가격에 즉시 진입
            activeHoldingSession(queue, state);
            given(marketService.convertUsdtToQty(any(), any(), any(), any())).willReturn("0.002");

            autoTradeService.closeQueuePosition(1L, 1L);
            // setActive·syncSession 전에 도착한 가격
            autoTradeService.onPriceUpdate(ExchangeType.BYBIT, "BTCUSDT", "50000.00");

            then(tradeFacade).should(never()).placeOrder(any(), any(), any(), any(), any());
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
            stubTicker();
            fakePositionSize.set(new BigDecimal("0.001"));
            given(tradeFacade.cancelOrder(any(), any(), any(), any(), any())).willReturn(OrderCancelResult.FAILED);

            assertThatThrownBy(() -> autoTradeService.closeQueuePosition(1L, 1L))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getExceptionMessage())
                    .isEqualTo(ExceptionMessage.QUEUE_POSITION_CLOSE_FAILED);

            // 시세 단계가 아니라 취소 단계에서 실패했는지 확인
            then(tradeFacade).should().cancelOrder(1L, "BTCUSDT", "q1-T-1", ExchangeType.BYBIT, TradeMode.MAIN);
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
            stubTicker();
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

        /** 첫 청산 주문은 거부(포지션 유지), 이후 주문은 접수되어 포지션 0 */
        private void stubFirstCloseRejectedThenAccepted() {
            java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willAnswer(inv -> {
                if (calls.incrementAndGet() == 1) throw new RuntimeException("주문 거부");
                fakePositionSize.set(BigDecimal.ZERO);
                return closeOrderResponse("close-2");
            });
        }

        /** 지정가 0.0009 체결 후 잔여 0.0001 끄기 청산이 거부된 세션을 준비 */
        private AutoTradeSessionDTO sessionAfterRejectedQueueClose(PatternQueue queue, QueueStateDTO state) {
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            stubTicker();
            fakePositionSize.set(new BigDecimal("0.0001"));
            given(tradeFacade.cancelOrder(any(), any(), any(), any(), any())).willReturn(OrderCancelResult.CANCELLED);
            stubFirstCloseRejectedThenAccepted();
            assertThatThrownBy(() -> autoTradeService.closeQueuePosition(1L, 1L)).isInstanceOf(CustomException.class);
            return session;
        }

        /** 잔여 재시도 결과 확인: 주문 수량, 부분 익절 이력 1건, 잔여 이력 금액(마진 10%), 투자 이력 합산 청산가 */
        private void assertRetryMergedPartialTp(TradeOrderType closeType, String expectedExitPrice) {
            assertThat(marketOrders()).hasSize(2).allSatisfy(o -> assertThat(o.getQty()).isEqualTo("0.0001"));
            ArgumentCaptor<TradeHistoryDTO> histories = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should(atLeast(1)).saveTradeHistory(histories.capture(), eq(TradeMode.MAIN));
            assertThat(histories.getAllValues()).filteredOn(h -> "tp-order-1".equals(h.getOrderId())).hasSize(1);
            TradeHistoryDTO closed = histories.getAllValues().stream()
                    .filter(h -> !"tp-order-1".equals(h.getOrderId())
                            && closeType.getTradeOrderType().equals(h.getOrderType())
                            && OrderResult.SUCCESS.equals(h.getOrderResult())).findFirst().orElseThrow();
            assertThat(closed.getAmount()).isEqualByComparingTo("10");
            ArgumentCaptor<InvestmentHistoryDTO> invest = ArgumentCaptor.forClass(InvestmentHistoryDTO.class);
            then(tradeFacade).should().saveInvestmentHistory(invest.capture(), any());
            assertThat(invest.getValue().getExitPrice()).isEqualByComparingTo(expectedExitPrice);
        }

        @Test
        @DisplayName("끄기 청산 거부 후 다시 끄기: 앞선 부분 익절을 합산하고 잔여 금액만 기록한다")
        void closeQueue_rejectedThenRetry_mergesPartialTp() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitState();
            sessionAfterRejectedQueueClose(queue, state);

            autoTradeService.closeQueuePosition(1L, 1L);

            // (55000×0.0009 + 51000×0.0001) / 0.001 = 54600
            assertRetryMergedPartialTp(TradeOrderType.LIQUIDATION, "54600");
            assertThat(state.getPartialTpQty()).isNull();
        }

        @Test
        @DisplayName("끄기 청산 거부 후 손절: 잔여만 청산하고 앞선 부분 익절을 합산하며 합산 손익으로 경로를 정한다")
        void closeQueue_rejectedThenStopLoss_mergesPartialTp() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitState();
            AutoTradeSessionDTO session = sessionAfterRejectedQueueClose(queue, state);

            autoTradeService.processPositionHolding(queue, state, session, "45000.00");

            // (55000×0.0009 + 45000×0.0001) / 0.001 = 54000
            assertRetryMergedPartialTp(TradeOrderType.LIQUIDATION, "54000");
            assertThat(session.getTradeLog()).anyMatch(l -> l.contains("부분 익절") && l.contains("익절 처리"));
            assertThat(state.getPartialTpQty()).isNull();
            // 합산 손익 ≥ 0 → 익절 경로 (1단계·같은 방향)
            assertThat(state.getCurrentStepLevel()).isEqualTo(1);
            assertThat(state.getDirection()).isEqualTo(Side.LONG);
            assertThat(state.getPhase()).isEqualTo(TradePhase.BLOCK_MATCHING);
        }

        @Test
        @DisplayName("끄기 청산 거부 후 익절가 도달: 잔여만 매도하고 앞선 부분 익절을 합산한다")
        void closeQueue_rejectedThenTakeProfit_mergesPartialTp() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitState();
            AutoTradeSessionDTO session = sessionAfterRejectedQueueClose(queue, state);
            stubTicker();

            autoTradeService.processPositionHolding(queue, state, session, "56000.00");

            // (55000×0.0009 + 56000×0.0001) / 0.001 = 55100
            assertRetryMergedPartialTp(TradeOrderType.SELL, "55100");
            assertThat(state.getPartialTpQty()).isNull();
            // 익절 → 1단계·같은 방향
            assertThat(state.getCurrentStepLevel()).isEqualTo(1);
            assertThat(state.getDirection()).isEqualTo(Side.LONG);
        }

        @Test
        @DisplayName("끄기 청산 거부 후 손절: 합산 손익 < 0이면 손절 경로로 판단한다")
        void closeQueue_rejectedThenStopLoss_negativeSum_stopLossPath() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitState();
            AutoTradeSessionDTO session = sessionAfterRejectedQueueClose(queue, state);

            // 익절분 +4.5, 잔여 (4000−50000)×0.0001 = −4.6 → 합산 음수
            autoTradeService.processPositionHolding(queue, state, session, "4000.00");

            // (55000×0.0009 + 4000×0.0001) / 0.001 = 49900
            assertRetryMergedPartialTp(TradeOrderType.LIQUIDATION, "49900");
            assertThat(session.getTradeLog()).anyMatch(l -> l.contains("부분 익절") && l.contains("손절 처리"));
        }

        @Test
        @DisplayName("부분 익절 후 틱 손절 주문 거부(포지션 잔존): 재시도 없이 큐를 끈다 (기존 동작)")
        void stopLoss_partialTp_rejected_positionRemains_deactivatesQueue() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitState();
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            fakePositionSize.set(new BigDecimal("0.0001"));
            given(tradeFacade.cancelOrder(any(), any(), any(), any(), any())).willReturn(OrderCancelResult.CANCELLED);
            stubFirstCloseRejectedThenAccepted();

            autoTradeService.processPositionHolding(queue, state, session, "45000.00");

            assertThat(marketOrders()).hasSize(1);
            assertThat(queue.isActive()).isFalse();
            assertThat(session.getQueueStates()).doesNotContainKey(1L);
            // 투자 이력은 남지 않음 (잔여 포지션은 수동 처리 대상)
            then(tradeFacade).should(never()).saveInvestmentHistory(any(), any());
        }

        @Test
        @DisplayName("부분 익절 후 틱 손절 거부(포지션 잔존): 꺼짐 사유에 부분 익절 수량과 수동 확인 안내를 넣는다")
        void stopLoss_partialTp_rejected_positionRemains_reasonGuidesManualCheck() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitState();
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            fakePositionSize.set(new BigDecimal("0.0001"));
            given(tradeFacade.cancelOrder(any(), any(), any(), any(), any())).willReturn(OrderCancelResult.CANCELLED);
            stubFirstCloseRejectedThenAccepted();

            autoTradeService.processPositionHolding(queue, state, session, "45000.00");

            assertThat(deactivateAlertReason())
                    .contains("청산 주문 실패")
                    .contains("부분 익절 0.0009")
                    .contains("잔여 포지션을 수동으로 확인해 주세요");
        }

        @Test
        @DisplayName("부분 익절 후 틱 손절 거부 + 포지션 재조회 실패: 잔여를 알 수 없으므로 같은 안내를 넣는다")
        void stopLoss_partialTp_rejected_positionUnknown_reasonGuidesManualCheck() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitState();
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            fakePositionSize.set(new BigDecimal("0.0001"));
            given(tradeFacade.cancelOrder(any(), any(), any(), any(), any())).willReturn(OrderCancelResult.CANCELLED);
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willAnswer(inv -> {
                fakePositionSize.set(null); // 거부 뒤 재조회부터 실패
                throw new RuntimeException("주문 거부");
            });

            autoTradeService.processPositionHolding(queue, state, session, "45000.00");

            assertThat(queue.isActive()).isFalse();
            assertThat(deactivateAlertReason())
                    .contains("부분 익절 0.0009")
                    .contains("잔여 포지션을 수동으로 확인해 주세요");
        }

        @Test
        @DisplayName("부분 익절 없이 틱 손절 거부: 부분 익절 안내를 넣지 않는다")
        void stopLoss_noPartialTp_rejected_reasonHasNoPartialTpGuide() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitState();
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            fakePositionSize.set(new BigDecimal("0.001"));
            given(tradeFacade.cancelOrder(any(), any(), any(), any(), any())).willReturn(OrderCancelResult.CANCELLED);
            stubFirstCloseRejectedThenAccepted();

            autoTradeService.processPositionHolding(queue, state, session, "45000.00");

            assertThat(queue.isActive()).isFalse();
            assertThat(deactivateAlertReason())
                    .contains("청산 주문 실패")
                    .doesNotContain("부분 익절")
                    .doesNotContain("잔여 포지션");
        }

        @Test
        @DisplayName("부분 익절 값 보유 + 포지션 0 확인 뒤 재조회 실패: 남은 포지션이 없으므로 수동 확인 안내를 넣지 않는다")
        void stopLoss_partialTpKept_positionConfirmedZero_lookupFails_reasonHasNoGuide() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitState();
            state.setPartialTpQty("0.0009");
            state.setPartialTpPrice(new BigDecimal("50500"));
            state.setPartialTpFee(BigDecimal.ZERO);
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            given(tradeFacade.cancelOrder(any(), any(), any(), any(), any())).willReturn(OrderCancelResult.CANCELLED);
            // 취소 후 조회는 0, handleCloseOrderFailure의 재조회는 실패
            given(tradeFacade.getPositionSize(any(), any(), any(), eq(TradeMode.MAIN)))
                    .willReturn(BigDecimal.ZERO)
                    .willReturn(null);

            autoTradeService.processPositionHolding(queue, state, session, "45000.00");

            assertThat(queue.isActive()).isFalse();
            assertThat(deactivateAlertReason())
                    .contains("포지션 0 확인")
                    .doesNotContain("잔여 포지션");
        }

        @Test
        @DisplayName("끄기 거부 후 손절: 잔여가 앞서 읽은 값과 다르면 거래소 잔여로 주문하고 합산 수량을 고친다")
        void closeQueue_rejectedThenStopLoss_positionChanged_usesExchangeRemaining() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitState();
            AutoTradeSessionDTO session = sessionAfterRejectedQueueClose(queue, state);
            fakePositionSize.set(new BigDecimal("0.0005")); // 앞서 읽은 잔여 0.0001이 틀렸던 경우

            autoTradeService.processPositionHolding(queue, state, session, "45000.00");

            assertThat(marketOrders().get(1).getQty()).isEqualTo("0.0005");
            ArgumentCaptor<InvestmentHistoryDTO> invest = ArgumentCaptor.forClass(InvestmentHistoryDTO.class);
            then(tradeFacade).should().saveInvestmentHistory(invest.capture(), any());
            // (55000×0.0005 + 45000×0.0005) / 0.001 = 50000
            assertThat(invest.getValue().getExitPrice()).isEqualByComparingTo("50000");
        }

        @Test
        @DisplayName("끄기 거부 후 익절가 도달: 잔여가 다르면 거래소 잔여로 매도한다")
        void closeQueue_rejectedThenTakeProfit_positionChanged_usesExchangeRemaining() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitState();
            AutoTradeSessionDTO session = sessionAfterRejectedQueueClose(queue, state);
            stubTicker();
            fakePositionSize.set(new BigDecimal("0.0005"));

            autoTradeService.processPositionHolding(queue, state, session, "56000.00");

            assertThat(marketOrders().get(1).getQty()).isEqualTo("0.0005");
        }

        @Test
        @DisplayName("끄기 거부 후 다시 끄기: 잔여가 다르면 합산 수량도 거래소 잔여 기준으로 고친다")
        void closeQueue_rejectedThenRetry_positionChanged_mergesWithExchangeRemaining() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitState();
            sessionAfterRejectedQueueClose(queue, state);
            fakePositionSize.set(new BigDecimal("0.0005"));

            autoTradeService.closeQueuePosition(1L, 1L);

            assertThat(marketOrders().get(1).getQty()).isEqualTo("0.0005");
            ArgumentCaptor<InvestmentHistoryDTO> invest = ArgumentCaptor.forClass(InvestmentHistoryDTO.class);
            then(tradeFacade).should().saveInvestmentHistory(invest.capture(), any());
            // (55000×0.0005 + 51000×0.0005) / 0.001 = 53000
            assertThat(invest.getValue().getExitPrice()).isEqualByComparingTo("53000");
        }

        @Test
        @DisplayName("끄기 거부 후 손절: 잔여 조회 실패면 주문을 보류하고 큐를 유지한다")
        void closeQueue_rejectedThenStopLoss_lookupFails_holdsOrder() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitState();
            AutoTradeSessionDTO session = sessionAfterRejectedQueueClose(queue, state);
            fakePositionSize.set(null);

            autoTradeService.processPositionHolding(queue, state, session, "45000.00");

            assertThat(marketOrders()).hasSize(1); // 앞선 거부 주문뿐
            assertThat(queue.isActive()).isTrue();
            assertThat(state.getTpCancelFailCount()).isEqualTo(1);
            assertThat(state.getPartialTpQty()).isEqualTo("0.0009");
        }

        @Test
        @DisplayName("끄기 거부 후 손절: 잔여 조회가 5회 연속 실패하면 큐를 끄고 수동 확인을 안내한다")
        void closeQueue_rejectedThenStopLoss_lookupFailsFiveTimes_deactivatesWithGuide() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitState();
            AutoTradeSessionDTO session = sessionAfterRejectedQueueClose(queue, state);
            fakePositionSize.set(null);

            for (int i = 0; i < 5; i++) {
                state.setCloseConfirmCheckedAt(0L); // 재시도 간격 제한 통과
                autoTradeService.processPositionHolding(queue, state, session, "45000.00");
            }

            assertThat(marketOrders()).hasSize(1);
            assertThat(queue.isActive()).isFalse();
            assertThat(deactivateAlertReason())
                    .contains("부분 익절 0.0009")
                    .contains("잔여 포지션을 수동으로 확인해 주세요");
        }

        @Test
        @DisplayName("끄기 거부 후 손절: 잔여가 이미 0이면 주문하지 않는다")
        void closeQueue_rejectedThenStopLoss_positionZero_noOrder() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitState();
            AutoTradeSessionDTO session = sessionAfterRejectedQueueClose(queue, state);
            fakePositionSize.set(BigDecimal.ZERO);

            autoTradeService.processPositionHolding(queue, state, session, "45000.00");

            assertThat(marketOrders()).hasSize(1);
            // 청산 내역을 찾지 못했으므로 기록 없이 대기로 전환
            assertThat(state.getCloseDetailWaitStartedAt()).isNotNull();
            then(tradeFacade).should(never()).saveInvestmentHistory(any(), any());
        }

        @Test
        @DisplayName("끄기 거부 후 손절: 포지션이 진입 수량 그대로면 부분 익절 없음으로 보고 전량 청산한다")
        void closeQueue_rejectedThenStopLoss_positionFull_clearsPartialTp() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitState();
            AutoTradeSessionDTO session = sessionAfterRejectedQueueClose(queue, state);
            fakePositionSize.set(new BigDecimal("0.001"));

            autoTradeService.processPositionHolding(queue, state, session, "45000.00");

            assertThat(marketOrders().get(1).getQty()).isEqualTo("0.001");
            ArgumentCaptor<InvestmentHistoryDTO> invest = ArgumentCaptor.forClass(InvestmentHistoryDTO.class);
            then(tradeFacade).should().saveInvestmentHistory(invest.capture(), any());
            assertThat(invest.getValue().getExitPrice()).isEqualByComparingTo("45000");
        }

        /** 부분 익절 뒤 잔여 청산까지 정상 접수되도록 준비하고 손절을 실행 (체결 조회는 실패 → 현재가 사용) */
        private void runStopLossAfterPartialTp(QueueStateDTO state) {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            fakePositionSize.set(new BigDecimal("0.0001"));
            given(tradeFacade.cancelOrder(any(), any(), any(), any(), any())).willReturn(OrderCancelResult.CANCELLED);
            stubCloseAcceptedExecutionFails();
            autoTradeService.processPositionHolding(queue, state, session, "45000.00");
        }

        /** 주문 ID별 거래소 손익 응답을 설정 (없는 주문은 null) */
        private void stubClosedPnl(java.util.Map<String, ClosedPnlDTO> byOrderId) {
            given(tradeFacade.getClosedPnl(any(), any(), any())).willAnswer(inv -> {
                ClosedPnlQueryDTO query = inv.getArgument(0);
                return byOrderId.get(query.getOrderId());
            });
        }

        private ClosedPnlDTO closedPnl(String pnl, String funding) {
            return ClosedPnlDTO.builder().closedPnl(pnl).fundingFee(funding).build();
        }

        @Test
        @DisplayName("합산 기록: 부분 익절과 잔여 청산의 거래소 손익·펀딩비를 더해 저장한다")
        void partialTp_merged_sumsExchangePnlAndFunding() {
            QueueStateDTO state = tpLimitState();
            state.setEntryTime(1000L);
            stubClosedPnl(java.util.Map.of(
                    "tp-order-1", closedPnl("4.0", "0"),
                    "close-1", closedPnl("-0.5", "0.03")));

            runStopLossAfterPartialTp(state);

            ArgumentCaptor<InvestmentHistoryDTO> invest = ArgumentCaptor.forClass(InvestmentHistoryDTO.class);
            then(tradeFacade).should().saveInvestmentHistory(invest.capture(), any());
            assertThat(invest.getValue().getProfitLoss()).isEqualByComparingTo("3.5");
            assertThat(invest.getValue().getFundingFee()).isEqualByComparingTo("0.03");
        }

        @Test
        @DisplayName("합산 기록: 진입 수수료와 펀딩비 계산에 쓸 진입 정보는 잔여 청산 조회에 넘긴다")
        void partialTp_merged_residualQueryHasEntryInfo() {
            QueueStateDTO state = tpLimitState();
            state.setEntryTime(1000L);
            stubClosedPnl(java.util.Map.of());

            runStopLossAfterPartialTp(state);

            ArgumentCaptor<ClosedPnlQueryDTO> queries = ArgumentCaptor.forClass(ClosedPnlQueryDTO.class);
            then(tradeFacade).should(atLeast(1)).getClosedPnl(queries.capture(), eq(1L), eq(TradeMode.MAIN));
            assertThat(queries.getAllValues()).filteredOn(q -> "close-1".equals(q.getOrderId()))
                    .singleElement().satisfies(q -> {
                        assertThat(q.getEntryTime()).isEqualTo(1000L);
                        assertThat(q.getEntryFee()).isNotNull();
                    });
        }

        @Test
        @DisplayName("합산 기록: 부분 익절 조회에는 진입 수수료 0, 펀딩 시작 시각 없음, 조회 범위 시작점을 넘긴다")
        void partialTp_merged_tpQueryHasNoEntryFeeOrFunding() {
            QueueStateDTO state = tpLimitState();
            state.setEntryTime(1000L);
            stubClosedPnl(java.util.Map.of("close-1", closedPnl("-0.5", "0.03")));

            runStopLossAfterPartialTp(state);

            ArgumentCaptor<ClosedPnlQueryDTO> queries = ArgumentCaptor.forClass(ClosedPnlQueryDTO.class);
            then(tradeFacade).should(atLeast(1)).getClosedPnl(queries.capture(), eq(1L), eq(TradeMode.MAIN));
            assertThat(queries.getAllValues()).filteredOn(q -> "tp-order-1".equals(q.getOrderId()))
                    .singleElement().satisfies(q -> {
                        assertThat(q.getEntryTime()).isNull();
                        assertThat(q.getEntryFee()).isEqualByComparingTo("0");
                        assertThat(q.getLookbackFrom()).isEqualTo(1000L);
                    });
        }

        @Test
        @DisplayName("합산 기록: 부분 익절 손익을 못 찾으면 잔여 손익만 쓰지 않고 계산식으로 저장한다")
        void partialTp_merged_tpPnlMissing_fallsBackToCalculated() {
            QueueStateDTO state = tpLimitState();
            state.setEntryTime(1000L);
            stubClosedPnl(java.util.Map.of("close-1", closedPnl("-0.5", "0.03")));

            runStopLossAfterPartialTp(state);

            ArgumentCaptor<InvestmentHistoryDTO> invest = ArgumentCaptor.forClass(InvestmentHistoryDTO.class);
            then(tradeFacade).should().saveInvestmentHistory(invest.capture(), any());
            // 청산가 (55000×0.0009 + 45000×0.0001) / 0.001 = 54000, 총손익 8%×100×10 = 80
            // 수수료 진입 0.0275 + 잔여 0.0025 + 익절 0.0099 = 0.0399 → 79.9601
            assertThat(invest.getValue().getExitPrice()).isEqualByComparingTo("54000");
            assertThat(invest.getValue().getProfitLoss()).isEqualByComparingTo("79.9601");
            assertThat(invest.getValue().getFundingFee()).isNull();
        }

        @Test
        @DisplayName("합산 기록: 부분 익절 조회가 예외를 던져도 계산식으로 투자 이력을 저장한다")
        void partialTp_merged_tpQueryThrows_fallsBackToCalculated() {
            QueueStateDTO state = tpLimitState();
            state.setEntryTime(1000L);
            given(tradeFacade.getClosedPnl(any(), any(), any())).willAnswer(inv -> {
                ClosedPnlQueryDTO query = inv.getArgument(0);
                if ("tp-order-1".equals(query.getOrderId())) throw new RuntimeException("조회 실패");
                return closedPnl("-0.5", "0.03");
            });

            runStopLossAfterPartialTp(state);

            ArgumentCaptor<InvestmentHistoryDTO> invest = ArgumentCaptor.forClass(InvestmentHistoryDTO.class);
            then(tradeFacade).should().saveInvestmentHistory(invest.capture(), any());
            // 청산가 (55000×0.0009 + 45000×0.0001) / 0.001 = 54000, 총손익 8%×100×10 = 80
            // 수수료 진입 0.0275 + 잔여 0.0025 + 익절 0.0099 = 0.0399 → 79.9601
            assertThat(invest.getValue().getExitPrice()).isEqualByComparingTo("54000");
            assertThat(invest.getValue().getProfitLoss()).isEqualByComparingTo("79.9601");
            assertThat(invest.getValue().getFundingFee()).isNull();
        }

        @Test
        @DisplayName("합산 기록: 익절 주문 ID가 없으면 부분 익절 손익을 조회하지 않고 계산식으로 저장한다")
        void partialTp_merged_noTpOrderId_fallsBackToCalculated() {
            QueueStateDTO state = tpLimitState();
            state.setTpOrderId(null);
            stubClosedPnl(java.util.Map.of("close-1", closedPnl("-0.5", "0.03")));

            runStopLossAfterPartialTp(state);

            ArgumentCaptor<ClosedPnlQueryDTO> queries = ArgumentCaptor.forClass(ClosedPnlQueryDTO.class);
            then(tradeFacade).should().getClosedPnl(queries.capture(), eq(1L), eq(TradeMode.MAIN));
            assertThat(queries.getValue().getOrderId()).isEqualTo("close-1");
            ArgumentCaptor<InvestmentHistoryDTO> invest = ArgumentCaptor.forClass(InvestmentHistoryDTO.class);
            then(tradeFacade).should().saveInvestmentHistory(invest.capture(), any());
            // 청산가 (55000×0.0009 + 45000×0.0001) / 0.001 = 54000, 총손익 8%×100×10 = 80
            // 수수료 진입 0.0275 + 잔여 0.0025 + 익절 0.0099 = 0.0399 → 79.9601
            assertThat(invest.getValue().getExitPrice()).isEqualByComparingTo("54000");
            assertThat(invest.getValue().getProfitLoss()).isEqualByComparingTo("79.9601");
            assertThat(invest.getValue().getFundingFee()).isNull();
        }

        /** 부분 익절 주문의 청산 내역 (잔여 내역이 반영되기 전에 최근 건으로 돌아오는 경우) */
        private PositionCloseDTO partialTpOrderClose() {
            return PositionCloseDTO.builder().userId(1L).exchangeType(ExchangeType.BYBIT).symbol("BTCUSDT")
                    .closeType(PositionCloseType.OTHER).orderId("tp-order-1").orderLinkId("q1-T-1")
                    .execPrice("55000").build();
        }

        @Test
        @DisplayName("부분 익절 뒤 포지션 0: 찾은 내역이 부분 익절 주문이면 잔여 내역으로 쓰지 않고 대기한다")
        void partialTpKept_positionZero_closeIsTpOrder_holdsForDetail() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitState();
            AutoTradeSessionDTO session = sessionAfterRejectedQueueClose(queue, state);
            fakePositionSize.set(BigDecimal.ZERO);
            given(tradeFacade.findLastClose(any(), any(), anyLong(), any(), any())).willReturn(partialTpOrderClose());
            given(tradeFacade.getClosedPnl(any(), any(), any())).willReturn(ClosedPnlDTO.builder().closedPnl("4.0").fundingFee("0").build());

            autoTradeService.processPositionHolding(queue, state, session, "45000.00");

            then(tradeFacade).should(never()).saveInvestmentHistory(any(), any());
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
            assertThat(state.getCloseDetailWaitStartedAt()).isNotNull();
            assertThat(queue.isActive()).isTrue();
        }

        @Test
        @DisplayName("부분 익절 뒤 재조정: 대기가 만료될 때까지 부분 익절 주문만 보이면 익절가로 기록하지 않고 큐를 끈다")
        void partialTpKept_reconcile_closeIsTpOrder_expired_noInvestment() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitState();
            sessionAfterRejectedQueueClose(queue, state);
            state.setCloseDetailWaitStartedAt(System.currentTimeMillis() - 6 * 60 * 1000L);
            fakePositionSize.set(BigDecimal.ZERO);
            given(tradeFacade.findLastClose(any(), any(), anyLong(), any(), any())).willReturn(partialTpOrderClose());

            autoTradeService.reconcileExternalCloses();

            then(tradeFacade).should(never()).saveInvestmentHistory(any(), any());
            assertThat(queue.isActive()).isFalse();
            // 잔여는 익절가로 기록하지 않고 가격 0·실패 사유 행만 남김
            ArgumentCaptor<TradeHistoryDTO> histories = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should(atLeast(1)).saveTradeHistory(histories.capture(), eq(TradeMode.MAIN));
            assertThat(histories.getAllValues()).filteredOn(h -> h.getErrorMessage() != null
                            && h.getErrorMessage().contains("체결가를 확인할 수 없습니다"))
                    .singleElement().satisfies(h -> {
                        assertThat(h.getOrderResult()).isEqualTo(OrderResult.FAILED);
                        assertThat(h.getExecutedPrice()).isEqualByComparingTo("0");
                    });
            assertThat(histories.getAllValues())
                    .filteredOn(h -> OrderResult.SUCCESS.equals(h.getOrderResult()))
                    .allSatisfy(h -> assertThat(h.getOrderId()).isEqualTo("tp-order-1"));
        }

        @Test
        @DisplayName("부분 익절 뒤 대기 복구: 보류 후 실제 잔여 내역이 오면 두 주문 손익을 한 번씩만 합산해 저장한다")
        void partialTpKept_heldThenResidualArrives_mergesOnce() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitState();
            AutoTradeSessionDTO session = sessionAfterRejectedQueueClose(queue, state);
            fakePositionSize.set(BigDecimal.ZERO);
            stubClosedPnl(java.util.Map.of(
                    "tp-order-1", closedPnl("4.0", "0"),
                    "residual-1", closedPnl("-0.5", "0.03")));
            given(tradeFacade.findLastClose(any(), any(), anyLong(), any(), any())).willReturn(partialTpOrderClose());

            // 1) 잔여 내역이 아직 없어 부분 익절 주문만 보임 → 보류
            autoTradeService.processPositionHolding(queue, state, session, "45000.00");
            then(tradeFacade).should(never()).saveInvestmentHistory(any(), any());
            assertThat(state.getCloseDetailWaitStartedAt()).isNotNull();

            // 2) 다음 재조정에서 실제 잔여 내역 도착
            given(tradeFacade.findLastClose(any(), any(), anyLong(), any(), any())).willReturn(
                    PositionCloseDTO.builder().userId(1L).exchangeType(ExchangeType.BYBIT).symbol("BTCUSDT")
                            .closeType(PositionCloseType.OTHER).orderId("residual-1").orderLinkId("")
                            .execPrice("45000").build());
            autoTradeService.reconcileExternalCloses();

            ArgumentCaptor<InvestmentHistoryDTO> invest = ArgumentCaptor.forClass(InvestmentHistoryDTO.class);
            then(tradeFacade).should().saveInvestmentHistory(invest.capture(), any());
            assertThat(invest.getValue().getProfitLoss()).isEqualByComparingTo("3.5"); // 4.0 + (-0.5), 익절 손익 1회
            assertThat(invest.getValue().getFundingFee()).isEqualByComparingTo("0.03");
            assertThat(invest.getValue().getExitPrice()).isEqualByComparingTo("54000");
            // 부분 익절 거래 이력은 다시 저장하지 않음
            ArgumentCaptor<TradeHistoryDTO> histories = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should(atLeast(1)).saveTradeHistory(histories.capture(), eq(TradeMode.MAIN));
            assertThat(histories.getAllValues()).filteredOn(h -> "tp-order-1".equals(h.getOrderId())).hasSize(1);
            assertThat(state.getCloseDetailWaitStartedAt()).isNull();
            assertThat(state.getPartialTpQty()).isNull();
        }

        @Test
        @DisplayName("부분 익절 뒤 비대기 재조정: 찾은 내역이 부분 익절 주문이면 잔여 내역으로 쓰지 않고 대기한다")
        void partialTpKept_reconcileNotWaiting_closeIsTpOrder_holdsForDetail() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitState();
            sessionAfterRejectedQueueClose(queue, state);
            fakePositionSize.set(BigDecimal.ZERO);
            given(tradeFacade.findLastClose(any(), any(), anyLong(), any(), any())).willReturn(partialTpOrderClose());

            autoTradeService.reconcileExternalCloses();

            then(tradeFacade).should(never()).saveInvestmentHistory(any(), any());
            assertThat(state.getCloseDetailWaitStartedAt()).isNotNull();
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
            assertThat(queue.isActive()).isTrue();
        }

        @Test
        @DisplayName("부분 익절 뒤 실시간 수신: 부분 익절 주문의 체결 알림은 잔여 청산으로 기록하지 않고 대기한다")
        void partialTpKept_realtime_closeIsTpOrder_holdsForDetail() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitState();
            sessionAfterRejectedQueueClose(queue, state);
            fakePositionSize.set(BigDecimal.ZERO);

            autoTradeService.onPositionClose(partialTpOrderClose());

            then(tradeFacade).should(never()).saveInvestmentHistory(any(), any());
            assertThat(state.getCloseDetailWaitStartedAt()).isNotNull();
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
            assertThat(queue.isActive()).isTrue();
        }

        @org.junit.jupiter.params.ParameterizedTest
        @org.junit.jupiter.params.provider.NullAndEmptySource
        @DisplayName("부분 익절 주문 ID 없음: 찾은 내역을 부분 익절 주문과 구분할 수 없으면 잔여 내역으로 쓰지 않고 대기한다")
        void partialTpKept_noTpOrderId_holdsForDetail(String missingOrderId) {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitState();
            state.setTpOrderId(missingOrderId);
            sessionAfterRejectedQueueClose(queue, state);
            fakePositionSize.set(BigDecimal.ZERO);
            given(tradeFacade.findLastClose(any(), any(), anyLong(), any(), any())).willReturn(partialTpOrderClose());

            autoTradeService.reconcileExternalCloses();

            then(tradeFacade).should(never()).saveInvestmentHistory(any(), any());
            assertThat(state.getCloseDetailWaitStartedAt()).isNotNull();
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
            assertThat(queue.isActive()).isTrue();
        }

        @Test
        @DisplayName("부분 익절 주문 ID 없음 + 실시간 수신: 체결 알림도 잔여 청산으로 기록하지 않고 대기한다")
        void partialTpKept_noTpOrderId_realtime_holdsForDetail() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitState();
            state.setTpOrderId(null);
            sessionAfterRejectedQueueClose(queue, state);
            fakePositionSize.set(BigDecimal.ZERO);

            autoTradeService.onPositionClose(partialTpOrderClose());

            then(tradeFacade).should(never()).saveInvestmentHistory(any(), any());
            assertThat(state.getCloseDetailWaitStartedAt()).isNotNull();
            assertThat(queue.isActive()).isTrue();
        }

        @Test
        @DisplayName("부분 익절 주문 ID 없음 + 대기 만료: 실제 잔여 내역이어도 믿지 않고 가격 0 실패 행을 남기고 큐를 끈다")
        void partialTpKept_noTpOrderId_expired_failedRowAndDeactivated() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitState();
            state.setTpOrderId(null);
            sessionAfterRejectedQueueClose(queue, state);
            state.setCloseDetailWaitStartedAt(System.currentTimeMillis() - 6 * 60 * 1000L);
            fakePositionSize.set(BigDecimal.ZERO);
            given(tradeFacade.findLastClose(any(), any(), anyLong(), any(), any())).willReturn(
                    PositionCloseDTO.builder().userId(1L).exchangeType(ExchangeType.BYBIT).symbol("BTCUSDT")
                            .closeType(PositionCloseType.OTHER).orderId("residual-1").orderLinkId("")
                            .execPrice("45000").build());

            autoTradeService.reconcileExternalCloses();

            then(tradeFacade).should(never()).saveInvestmentHistory(any(), any());
            assertThat(queue.isActive()).isFalse();
            ArgumentCaptor<TradeHistoryDTO> histories = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should(atLeast(1)).saveTradeHistory(histories.capture(), eq(TradeMode.MAIN));
            assertThat(histories.getAllValues()).filteredOn(h -> h.getErrorMessage() != null
                            && h.getErrorMessage().contains("체결가를 확인할 수 없습니다"))
                    .singleElement().satisfies(h -> {
                        assertThat(h.getOrderResult()).isEqualTo(OrderResult.FAILED);
                        assertThat(h.getExecutedPrice()).isEqualByComparingTo("0");
                    });
            // 잔여 내역의 체결가로 성공 행을 남기지 않음
            assertThat(histories.getAllValues())
                    .noneMatch(h -> "residual-1".equals(h.getOrderId()) && OrderResult.SUCCESS.equals(h.getOrderResult()));
        }

        @Test
        @DisplayName("합산 기록: 잔여 청산 손익 조회가 예외를 던져도 계산식으로 투자 이력을 저장한다")
        void partialTp_merged_residualQueryThrows_fallsBackToCalculated() {
            QueueStateDTO state = tpLimitState();
            state.setEntryTime(1000L);
            given(tradeFacade.getClosedPnl(any(), any(), any())).willThrow(new RuntimeException("조회 실패"));

            runStopLossAfterPartialTp(state);

            ArgumentCaptor<InvestmentHistoryDTO> invest = ArgumentCaptor.forClass(InvestmentHistoryDTO.class);
            then(tradeFacade).should().saveInvestmentHistory(invest.capture(), any());
            // 청산가 (55000×0.0009 + 45000×0.0001) / 0.001 = 54000, 총손익 8%×100×10 = 80
            // 수수료 진입 0.0275 + 잔여 0.0025 + 익절 0.0099 = 0.0399 → 79.9601
            assertThat(invest.getValue().getExitPrice()).isEqualByComparingTo("54000");
            assertThat(invest.getValue().getProfitLoss()).isEqualByComparingTo("79.9601");
            assertThat(invest.getValue().getFundingFee()).isNull();
        }

        @Test
        @DisplayName("일반 청산: 손익 조회가 예외를 던져도 계산식으로 투자 이력을 저장한다")
        void normalClose_pnlQueryThrows_fallsBackToCalculated() {
            QueueStateDTO state = tpLimitState();
            state.setEntryTime(1000L);
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            fakePositionSize.set(new BigDecimal("0.001")); // 익절 체결 없이 전량 보유
            given(tradeFacade.cancelOrder(any(), any(), any(), any(), any())).willReturn(OrderCancelResult.CANCELLED);
            stubCloseAcceptedExecutionFails();
            given(tradeFacade.getClosedPnl(any(), any(), any())).willThrow(new RuntimeException("조회 실패"));

            autoTradeService.processPositionHolding(queue, state, session, "45000.00");

            ArgumentCaptor<InvestmentHistoryDTO> invest = ArgumentCaptor.forClass(InvestmentHistoryDTO.class);
            then(tradeFacade).should().saveInvestmentHistory(invest.capture(), any());
            // 총손익 -10%×100×10 = -100, 수수료 진입 0.0275 + 청산 0.0248 = 0.0523
            assertThat(invest.getValue().getProfitLoss()).isEqualByComparingTo("-100.0523");
            assertThat(invest.getValue().getFundingFee()).isNull();
        }

        /** 지정가가 걸린 보유 세션을 시세 스텁 없이 준비 (잔여 수량 0.001) */
        private AutoTradeSessionDTO priceCheckSession(QueueStateDTO state) {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            fakePositionSize.set(new BigDecimal("0.001"));
            given(tradeFacade.cancelOrder(any(), any(), any(), any(), any())).willReturn(OrderCancelResult.CANCELLED);
            return session;
        }

        /** 청산 주문은 주문 ID와 함께 접수되어 포지션이 0이 되지만, 실체결 조회는 실패하도록 설정 */
        private void stubCloseAcceptedExecutionFails() {
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willAnswer(inv -> {
                fakePositionSize.set(BigDecimal.ZERO);
                return closeOrderResponse("close-1");
            });
            given(tradeFacade.getExecution(eq("close-1"), any(), any(), any(), any(), any())).willReturn(null);
        }

        /** 실전 + 체결가 불명: 취소·시장가 청산은 진행하고, 가격 0·실패 사유 행만 남기며 투자 이력 없이 큐를 분리하는지 확인 */
        private void assertClosedWithUnknownPrice(QueueStateDTO state, AutoTradeSessionDTO session) {
            stubCloseAcceptedExecutionFails();

            autoTradeService.closeQueuePosition(1L, 1L);

            then(tradeFacade).should().cancelOrder(1L, "BTCUSDT", "q1-T-1", ExchangeType.BYBIT, TradeMode.MAIN);
            List<OrderRequestDTO> orders = marketOrders();
            assertThat(orders).hasSize(1);
            assertThat(orders.get(0).getQty()).isEqualTo("0.001");
            assertThat(orders.get(0).isReduceOnly()).isTrue();
            ArgumentCaptor<TradeHistoryDTO> historyCaptor = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should().saveTradeHistory(historyCaptor.capture(), eq(TradeMode.MAIN));
            TradeHistoryDTO saved = historyCaptor.getValue();
            // 주문 ID가 있는 실제 경로: 실체결을 조회했고, 실패 행으로 거래소 주문을 찾을 수 있어야 함
            then(tradeFacade).should().getExecution(eq("close-1"), any(), any(), any(), any(), any());
            assertThat(saved.getOrderId()).isEqualTo("close-1");
            assertThat(saved.getOrderResult()).isEqualTo(OrderResult.FAILED);
            assertThat(saved.getExecutedPrice()).isEqualByComparingTo("0");
            assertThat(saved.getFee()).isNull();
            assertThat(saved.getErrorMessage()).startsWith("[이력 저장 실패] 체결은 완료됨").contains("체결가를 확인할 수 없습니다");
            then(tradeFacade).should(never()).saveInvestmentHistory(any(), any());
            assertThat(session.getQueueStates()).doesNotContainKey(1L);
            assertThat(session.getTradeLog()).anyMatch(l -> l.contains("[경고]") && l.contains("거래소 내역을 확인"));
        }

        @Test
        @DisplayName("큐 종료 청산(실전): 티커가 없어도 청산하고, 체결가를 모르면 가격 0·실패 사유를 남긴다")
        void closeQueue_main_nullTicker_closesWithFailedRecord() {
            QueueStateDTO state = tpLimitState();
            AutoTradeSessionDTO session = priceCheckSession(state);
            given(marketService.getTicker(ExchangeType.BYBIT, "BTCUSDT")).willReturn(null);

            assertClosedWithUnknownPrice(state, session);
        }

        @Test
        @DisplayName("큐 종료 청산(실전): lastPrice가 null이어도 청산하고, 체결가를 모르면 가격 0·실패 사유를 남긴다")
        void closeQueue_main_nullLastPrice_closesWithFailedRecord() {
            QueueStateDTO state = tpLimitState();
            AutoTradeSessionDTO session = priceCheckSession(state);
            given(marketService.getTicker(ExchangeType.BYBIT, "BTCUSDT"))
                    .willReturn(TickerDTO.builder().lastPrice(null).build());

            assertClosedWithUnknownPrice(state, session);
        }

        @Test
        @DisplayName("큐 종료 청산(실전): lastPrice가 빈 값이어도 청산하고, 체결가를 모르면 가격 0·실패 사유를 남긴다")
        void closeQueue_main_emptyLastPrice_closesWithFailedRecord() {
            QueueStateDTO state = tpLimitState();
            AutoTradeSessionDTO session = priceCheckSession(state);
            given(marketService.getTicker(ExchangeType.BYBIT, "BTCUSDT"))
                    .willReturn(TickerDTO.builder().lastPrice("").build());

            assertClosedWithUnknownPrice(state, session);
        }

        @Test
        @DisplayName("큐 종료 청산(실전): lastPrice가 숫자가 아니어도 청산하고, 체결가를 모르면 가격 0·실패 사유를 남긴다")
        void closeQueue_main_nonNumericLastPrice_closesWithFailedRecord() {
            QueueStateDTO state = tpLimitState();
            AutoTradeSessionDTO session = priceCheckSession(state);
            given(marketService.getTicker(ExchangeType.BYBIT, "BTCUSDT"))
                    .willReturn(TickerDTO.builder().lastPrice("abc").build());

            assertClosedWithUnknownPrice(state, session);
        }

        @Test
        @DisplayName("큐 종료 청산(실전): 시세 조회 예외여도 청산하고, 체결가를 모르면 가격 0·실패 사유를 남긴다")
        void closeQueue_main_tickerException_closesWithFailedRecord() {
            QueueStateDTO state = tpLimitState();
            AutoTradeSessionDTO session = priceCheckSession(state);
            given(marketService.getTicker(ExchangeType.BYBIT, "BTCUSDT")).willThrow(new IllegalStateException("시세 오류"));

            assertClosedWithUnknownPrice(state, session);
        }

        @Test
        @DisplayName("큐 종료 청산(실전): 시세가 없어도 실체결을 받으면 실체결가·실수수료로 이력과 투자 이력을 저장한다")
        void closeQueue_main_noPriceWithExecution_recordsActual() {
            QueueStateDTO state = tpLimitState();
            AutoTradeSessionDTO session = priceCheckSession(state);
            given(marketService.getTicker(ExchangeType.BYBIT, "BTCUSDT")).willReturn(null);
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willAnswer(inv -> {
                fakePositionSize.set(BigDecimal.ZERO);
                return closeOrderResponse("close-1");
            });
            given(tradeFacade.getExecution(any(), any(), any(), any(), any(), any()))
                    .willReturn(ExecutionDTO.builder().execPrice("50500").execFee("0.03").build());

            autoTradeService.closeQueuePosition(1L, 1L);

            ArgumentCaptor<TradeHistoryDTO> historyCaptor = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should().saveTradeHistory(historyCaptor.capture(), eq(TradeMode.MAIN));
            assertThat(historyCaptor.getValue().getOrderResult()).isEqualTo(OrderResult.SUCCESS);
            assertThat(historyCaptor.getValue().getExecutedPrice()).isEqualByComparingTo("50500");
            assertThat(historyCaptor.getValue().getFee()).isEqualByComparingTo("0.03");
            ArgumentCaptor<InvestmentHistoryDTO> investCaptor = ArgumentCaptor.forClass(InvestmentHistoryDTO.class);
            then(tradeFacade).should().saveInvestmentHistory(investCaptor.capture(), eq(TradeMode.MAIN));
            assertThat(investCaptor.getValue().getExitPrice()).isEqualByComparingTo("50500");
            assertThat(session.getQueueStates()).doesNotContainKey(1L);
        }

        @Test
        @DisplayName("큐 종료 청산(실전): 시세가 없어도 포지션이 이미 0이면 청산 내역 기준으로 처리하고 큐를 분리한다")
        void closeQueue_main_zeroPositionNoPrice_detaches() {
            QueueStateDTO state = tpLimitState();
            AutoTradeSessionDTO session = priceCheckSession(state);
            fakePositionSize.set(BigDecimal.ZERO);
            given(marketService.getTicker(ExchangeType.BYBIT, "BTCUSDT")).willReturn(null);
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
        @DisplayName("큐 종료 청산(실전): 시세 없음 + 부분 체결이면 체결분은 정상 기록하고 잔여 수량만 청산한다")
        void closeQueue_main_partialTpNoPrice_closesRemaining() {
            QueueStateDTO state = tpLimitState();
            state.setEntryMargin(new BigDecimal("5"));
            AutoTradeSessionDTO session = priceCheckSession(state);
            fakePositionSize.set(new BigDecimal("0.0004"));
            given(marketService.getTicker(ExchangeType.BYBIT, "BTCUSDT")).willReturn(null);
            stubCloseAcceptedExecutionFails();

            autoTradeService.closeQueuePosition(1L, 1L);

            List<OrderRequestDTO> orders = marketOrders();
            assertThat(orders).hasSize(1);
            assertThat(orders.get(0).getQty()).isEqualTo("0.0004");
            ArgumentCaptor<TradeHistoryDTO> historyCaptor = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should(times(2)).saveTradeHistory(historyCaptor.capture(), eq(TradeMode.MAIN));
            TradeHistoryDTO tpHistory = historyCaptor.getAllValues().get(0);
            TradeHistoryDTO closeHistory = historyCaptor.getAllValues().get(1);
            assertThat(tpHistory.getOrderResult()).isEqualTo(OrderResult.SUCCESS);
            assertThat(tpHistory.getExecutedPrice()).isEqualByComparingTo("55000");
            assertThat(tpHistory.getAmount()).isEqualByComparingTo("3");
            assertThat(closeHistory.getOrderResult()).isEqualTo(OrderResult.FAILED);
            assertThat(closeHistory.getExecutedPrice()).isEqualByComparingTo("0");
            assertThat(closeHistory.getAmount()).isEqualByComparingTo("2");
            assertThat(closeHistory.getOrderId()).isEqualTo("close-1");
            then(tradeFacade).should(never()).saveInvestmentHistory(any(), any());
            assertThat(session.getQueueStates()).doesNotContainKey(1L);
        }

        @Test
        @DisplayName("큐 종료 청산(실전): 시세 없음 + 청산 주문 거부면 실패하고 큐를 유지한다")
        void closeQueue_main_noPriceOrderRejected_throwsAndKeeps() {
            QueueStateDTO state = tpLimitState();
            AutoTradeSessionDTO session = priceCheckSession(state);
            given(marketService.getTicker(ExchangeType.BYBIT, "BTCUSDT")).willReturn(null);
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willThrow(new RuntimeException("주문 거부"));

            assertThatThrownBy(() -> autoTradeService.closeQueuePosition(1L, 1L))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getExceptionMessage())
                    .isEqualTo(ExceptionMessage.QUEUE_POSITION_CLOSE_FAILED);

            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
            assertThat(session.getQueueStates()).containsKey(1L);
        }

        @Test
        @DisplayName("큐 종료 청산(실전): 시세 없음 + 주문 후 포지션 잔존이면 청산 확인 대기로 두고, 재시도 때 추가 주문 없이 분리한다")
        void closeQueue_main_noPriceCloseNotConfirmed_awaitsConfirm() {
            QueueStateDTO state = tpLimitState();
            AutoTradeSessionDTO session = priceCheckSession(state);
            given(marketService.getTicker(ExchangeType.BYBIT, "BTCUSDT")).willReturn(null);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(new BigDecimal("0.001"));

            assertThatThrownBy(() -> autoTradeService.closeQueuePosition(1L, 1L))
                    .isInstanceOf(CustomException.class);
            assertThat(state.getPendingConfirmType()).isNotNull();
            assertThat(state.isStopRequested()).isTrue();
            assertThat(session.getQueueStates()).containsKey(1L);

            // 재시도: 포지션 0 확인 → 추가 주문 없이 세션에서 분리
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(BigDecimal.ZERO);
            autoTradeService.closeQueuePosition(1L, 1L);

            assertThat(marketOrders()).hasSize(1);
            assertThat(session.getQueueStates()).doesNotContainKey(1L);
        }

        @Test
        @DisplayName("큐 종료 청산(모의): 시세가 없으면 체결가를 정할 수 없어 주문·이력 없이 실패하고 큐를 유지한다")
        void closeQueue_sim_noPrice_failsAndKeeps() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitState();
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            session.setTradeMode(TradeMode.SIM);
            given(marketService.getTicker(ExchangeType.BYBIT, "BTCUSDT")).willReturn(null);

            assertThatThrownBy(() -> autoTradeService.closeQueuePosition(1L, 1L))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getExceptionMessage())
                    .isEqualTo(ExceptionMessage.QUEUE_POSITION_CLOSE_FAILED);

            then(tradeFacade).should(never()).cancelOrder(any(), any(), any(), any(), any());
            assertThat(state.getTpOrderLinkId()).isEqualTo("q1-T-1");
            assertThat(marketOrders()).isEmpty();
            then(tradeFacade).should(never()).saveTradeHistory(any(), any());
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
            assertThat(session.getQueueStates()).containsKey(1L);
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
        @DisplayName("재조정: 포지션 0인데 청산 내역이 없으면 진입가로 기록하지 않고 보류한다 (지정가도 그대로)")
        void reconcile_closeDetailMissing_holds() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitHoldingState();
            state.setActivePatternId(100L);
            state.setEntryTime(1_700_000_000_000L);
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(BigDecimal.ZERO);
            given(tradeFacade.findLastClose(any(), any(), anyLong(), any(), any())).willReturn(closeWithoutDetail());

            autoTradeService.reconcileExternalCloses();

            assertThat(queue.isActive()).isTrue();
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
            assertThat(state.getCloseDetailWaitStartedAt()).isNotNull();
            assertThat(state.getTpOrderLinkId()).isEqualTo("q1-T-1");
            then(tradeFacade).should(never()).cancelOrder(any(), any(), any(), any(), any());
            then(tradeFacade).should(never()).saveTradeHistory(any(), any());
            then(tradeFacade).should(never()).saveInvestmentHistory(any(), any());
            assertThat(session.getTradeLog()).noneMatch(l -> l.contains("[외부 청산]"));
        }

        @Test
        @DisplayName("재조정: 보류 후 내역이 반영되면 실체결가로 기록하고 익절대로 1단계로 돌아간다")
        void reconcile_closeDetailArrives_recordsActualPrice() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitHoldingState();
            state.setActivePatternId(100L);
            state.setEntryTime(1_700_000_000_000L);
            activeHoldingSession(queue, state);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(BigDecimal.ZERO);
            given(tradeFacade.findLastClose(any(), any(), anyLong(), any(), any()))
                    .willReturn(closeWithoutDetail())
                    .willReturn(closeEvent(PositionCloseType.OTHER, "q1-T-1"));

            autoTradeService.reconcileExternalCloses();
            autoTradeService.reconcileExternalCloses();

            ArgumentCaptor<TradeHistoryDTO> saved = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should().saveTradeHistory(saved.capture(), eq(TradeMode.MAIN));
            assertThat(saved.getValue().getOrderResult()).isEqualTo(OrderResult.SUCCESS);
            assertThat(saved.getValue().getExecutedPrice()).isEqualByComparingTo("55600");
            then(tradeFacade).should(times(1)).saveInvestmentHistory(any(), any());
            assertThat(queue.isActive()).isTrue();
            assertThat(state.getPhase()).isEqualTo(TradePhase.BLOCK_MATCHING);
            assertThat(state.getCurrentStepLevel()).isEqualTo(1);
            assertThat(state.getCloseDetailWaitStartedAt()).isNull();
        }

        @Test
        @DisplayName("재조정: 대기 시간 안이면 계속 보류한다 (시작 시각은 유지)")
        void reconcile_closeDetailWithinLimit_keepsHolding() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            long startedAt = System.currentTimeMillis() - 4 * 60 * 1000L;
            state.setCloseDetailWaitStartedAt(startedAt);
            activeHoldingSession(queue, state);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(BigDecimal.ZERO);
            given(tradeFacade.findLastClose(any(), any(), anyLong(), any(), any())).willReturn(closeWithoutDetail());

            autoTradeService.reconcileExternalCloses();

            assertThat(queue.isActive()).isTrue();
            assertThat(state.getCloseDetailWaitStartedAt()).isEqualTo(startedAt);
            then(tradeFacade).should(never()).saveTradeHistory(any(), any());
        }

        @Test
        @DisplayName("재조정: 대기 시간이 지나도 내역이 없으면 가격 0·실패 사유를 남기고 투자 이력 없이 큐를 끈다")
        void reconcile_closeDetailExpired_recordsFailedAndDeactivates() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            state.setCloseDetailWaitStartedAt(System.currentTimeMillis() - 5 * 60 * 1000L - 1000L);
            activeHoldingSession(queue, state);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(BigDecimal.ZERO);
            given(tradeFacade.findLastClose(any(), any(), anyLong(), any(), any())).willReturn(closeWithoutDetail());

            autoTradeService.reconcileExternalCloses();

            assertUnknownCloseRecorded();
            assertThat(queue.isActive()).isFalse();
            assertThat(deactivateAlertReason()).contains("청산 내역 확인 실패").doesNotContain("외부 청산");
        }

        @Test
        @DisplayName("보류 중 TP·SL 가격에 도달해도 주문과 취소를 보내지 않는다")
        void holding_whileCloseDetailWaiting_noOrders() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            state.setCloseDetailWaitStartedAt(System.currentTimeMillis());
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);

            autoTradeService.processPositionHolding(queue, state, session, "55500.00");
            autoTradeService.processPositionHolding(queue, state, session, "44000.00");

            then(tradeFacade).should(never()).placeOrder(any(), any(), any(), any(), any());
            then(tradeFacade).should(never()).cancelOrder(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("보류 중 재조정에서 포지션이 보이면 보류를 풀어 TP/SL 감시를 재개한다")
        void reconcile_positionReappears_clearsCloseDetailWait() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            state.setCloseDetailWaitStartedAt(System.currentTimeMillis());
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(new BigDecimal("0.001"));

            autoTradeService.reconcileExternalCloses();

            assertThat(state.getCloseDetailWaitStartedAt()).isNull();
            autoTradeService.processPositionHolding(queue, state, session, "55500.00");
            then(tradeFacade).should(atLeastOnce()).placeOrder(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("큐 끄기 + 포지션 0 + 내역 없음: 실패 응답하되 끄기 요청을 기억한다 (이력 없음)")
        void closeQueue_closeDetailMissing_failsAndRemembersStop() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            activeHoldingSession(queue, state);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(BigDecimal.ZERO);
            given(tradeFacade.findLastClose(any(), any(), anyLong(), any(), any())).willReturn(closeWithoutDetail());

            assertThatThrownBy(() -> autoTradeService.closeQueuePosition(1L, 1L))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getExceptionMessage())
                    .isEqualTo(ExceptionMessage.QUEUE_POSITION_CLOSE_FAILED);

            assertThat(state.isStopRequested()).isTrue();
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
            then(tradeFacade).should(never()).placeOrder(any(), any(), any(), any(), any());
            then(tradeFacade).should(never()).saveTradeHistory(any(), any());
        }

        @Test
        @DisplayName("큐 끄기 실패 후 익절 내역이 반영되면: 실체결로 기록하고 1단계 재진입 없이 큐를 끈다")
        void closeQueue_closeDetailMissing_thenArrives_deactivatesNoReentry() {
            assertStopRememberedUntilCloseFound(closeWithoutDetail());
        }

        @Test
        @DisplayName("큐 끄기 때 내역 조회가 실패(null)했다가 익절 내역이 반영되어도: 재진입 없이 큐를 끈다")
        void closeQueue_lookupFailed_thenArrives_deactivatesNoReentry() {
            assertStopRememberedUntilCloseFound(null);
        }

        /** 끄기 실패(첫 조회 결과 firstLookup) → 재조정에서 익절 내역 발견 시 큐가 꺼지고 다음 진입 상태로 가지 않는지 확인 */
        private void assertStopRememberedUntilCloseFound(PositionCloseDTO firstLookup) {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(BigDecimal.ZERO);
            given(tradeFacade.findLastClose(any(), any(), anyLong(), any(), any()))
                    .willReturn(firstLookup)
                    .willReturn(closeEvent(PositionCloseType.TAKE_PROFIT, ""));

            assertThatThrownBy(() -> autoTradeService.closeQueuePosition(1L, 1L)).isInstanceOf(CustomException.class);
            autoTradeService.reconcileExternalCloses();

            ArgumentCaptor<TradeHistoryDTO> saved = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should().saveTradeHistory(saved.capture(), eq(TradeMode.MAIN));
            assertThat(saved.getValue().getOrderResult()).isEqualTo(OrderResult.SUCCESS);
            assertThat(saved.getValue().getExecutedPrice()).isEqualByComparingTo("55600");
            assertThat(queue.isActive()).isFalse();
            assertThat(session.getQueueStates()).doesNotContainKey(1L);
            assertThat(deactivateAlertReason()).contains("큐 종료 요청");
            then(tradeFacade).should(never()).placeOrder(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("큐 끄기 + 대기 시간 경과 + 내역 없음: 가격 0·실패 사유를 남기고 큐를 분리한다")
        void closeQueue_closeDetailExpired_recordsFailedAndDetaches() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            state.setCloseDetailWaitStartedAt(System.currentTimeMillis() - 5 * 60 * 1000L - 1000L);
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(BigDecimal.ZERO);
            given(tradeFacade.findLastClose(any(), any(), anyLong(), any(), any())).willReturn(closeWithoutDetail());

            autoTradeService.closeQueuePosition(1L, 1L);

            assertUnknownCloseRecorded();
            assertThat(session.getQueueStates()).doesNotContainKey(1L);
        }

        /** 거래소 내역 없음: 방식 불명(OTHER)에 주문 ID·체결가가 없는 청산 건 */
        private PositionCloseDTO closeWithoutDetail() {
            return PositionCloseDTO.builder().userId(1L).exchangeType(ExchangeType.BYBIT)
                    .symbol("BTCUSDT").closeType(PositionCloseType.OTHER).build();
        }

        /** 체결가 불명 기록: 가격 0·실패 사유 행 1건, 투자 이력 없음 */
        private void assertUnknownCloseRecorded() {
            ArgumentCaptor<TradeHistoryDTO> saved = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should().saveTradeHistory(saved.capture(), eq(TradeMode.MAIN));
            assertThat(saved.getValue().getOrderResult()).isEqualTo(OrderResult.FAILED);
            assertThat(saved.getValue().getExecutedPrice()).isEqualByComparingTo("0");
            assertThat(saved.getValue().getFee()).isNull();
            assertThat(saved.getValue().getErrorMessage()).contains("체결가를 확인할 수 없습니다");
            then(tradeFacade).should(never()).saveInvestmentHistory(any(), any());
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

        @ParameterizedTest
        @org.junit.jupiter.params.provider.CsvSource({"q1-S-1760000000000,SELL", "q1-L-1760000000000,LIQUIDATION"})
        @DisplayName("재조정: 대기가 아니어도 포지션 0 + 앱 주문 내역이면 응답 유실 건으로 기록하고 큐를 끈다")
        void reconcile_notWaiting_ownCloseFound_recordsAndDeactivates(String linkId, TradeOrderType expectedType) {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            state.setEntryTime(1_700_000_000_000L);
            activeHoldingSession(queue, state);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(BigDecimal.ZERO);
            given(tradeFacade.findLastClose(any(), any(), anyLong(), any(), any()))
                    .willReturn(closeEvent(PositionCloseType.OTHER, linkId));

            autoTradeService.reconcileExternalCloses();

            ArgumentCaptor<TradeHistoryDTO> saved = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should().saveTradeHistory(saved.capture(), eq(TradeMode.MAIN));
            assertThat(saved.getValue().getOrderType()).isEqualTo(expectedType.getTradeOrderType());
            assertThat(saved.getValue().getExecutedPrice()).isEqualByComparingTo("55600");
            assertThat(queue.isActive()).isFalse();
            assertThat(deactivateAlertReason()).contains("앱 주문 응답 유실");
            then(tradeFacade).should(never()).placeOrder(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("재조정: 청산 확인 대기 중인 큐는 앱 주문 내역을 찾아도 처리하지 않고 대기를 유지한다")
        void reconcile_pendingConfirm_ownCloseFound_keepsPending() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            state.setEntryTime(1_700_000_000_000L);
            state.setPendingConfirmType(TradeOrderType.SELL);
            activeHoldingSession(queue, state);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(BigDecimal.ZERO);
            given(tradeFacade.findLastClose(any(), any(), anyLong(), any(), any()))
                    .willReturn(closeEvent(PositionCloseType.OTHER, "q1-S-1760000000000"));

            autoTradeService.reconcileExternalCloses();

            assertThat(state.getPendingConfirmType()).isEqualTo(TradeOrderType.SELL);
            assertThat(queue.isActive()).isTrue();
            then(tradeFacade).should(never()).saveTradeHistory(any(), any());
        }

        @Test
        @DisplayName("일시 해제 + 주문 실패 + 포지션 0 + 내역 없음: 대기로 돌아가 다음 틱에 주문을 내지 않는다")
        void holding_suspended_closeFails_positionZero_noDetail_stopsOrders() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            state.setCloseDetailWaitStartedAt(System.currentTimeMillis());
            state.setCloseDetailWaitSuspended(true);
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willThrow(new RuntimeException("주문 거부"));
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(BigDecimal.ZERO);
            given(tradeFacade.findLastClose(any(), any(), anyLong(), any(), any())).willReturn(null);

            autoTradeService.processPositionHolding(queue, state, session, "55500.00");
            autoTradeService.processPositionHolding(queue, state, session, "55500.00");

            assertThat(state.isCloseDetailWaitSuspended()).isFalse();
            then(tradeFacade).should(times(1)).placeOrder(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("대기 없음 + 주문 실패 + 포지션 0 + 내역 없음: 대기를 시작해 다음 틱에 주문을 내지 않는다")
        void holding_closeFails_positionZero_noDetail_startsWait() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willThrow(new RuntimeException("주문 거부"));
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(BigDecimal.ZERO);
            given(tradeFacade.findLastClose(any(), any(), anyLong(), any(), any())).willReturn(null);

            autoTradeService.processPositionHolding(queue, state, session, "55500.00");
            autoTradeService.processPositionHolding(queue, state, session, "55500.00");

            assertThat(state.getCloseDetailWaitStartedAt()).isNotNull();
            then(tradeFacade).should(times(1)).placeOrder(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("주문 실패로 시작한 대기도 5분이 지나면 재조정에서 만료되어 큐를 끈다")
        void holding_closeFails_noDetail_waitExpires_deactivates() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willThrow(new RuntimeException("주문 거부"));
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(BigDecimal.ZERO);
            given(tradeFacade.findLastClose(any(), any(), anyLong(), any(), any())).willReturn(null);
            autoTradeService.processPositionHolding(queue, state, session, "55500.00");
            assertThat(state.getCloseDetailWaitStartedAt()).isNotNull();

            state.setCloseDetailWaitStartedAt(System.currentTimeMillis() - 5 * 60 * 1000L - 1000L);
            autoTradeService.reconcileExternalCloses();

            assertThat(queue.isActive()).isFalse();
        }

        @ParameterizedTest
        @org.junit.jupiter.params.provider.ValueSource(strings = {"q1-L-1759999999999", "q2-S-1760000000001"})
        @DisplayName("재조정: 직전 사이클이나 다른 큐의 앱 청산 건은 현재 포지션의 청산으로 기록하지 않는다")
        void reconcile_staleOwnClose_notRecorded(String linkId) {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            state.setEntryTime(1_760_000_000_000L);
            activeHoldingSession(queue, state);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(BigDecimal.ZERO);
            given(tradeFacade.findLastClose(any(), any(), anyLong(), any(), any()))
                    .willReturn(closeEvent(PositionCloseType.OTHER, linkId));

            autoTradeService.reconcileExternalCloses();

            then(tradeFacade).should(never()).saveTradeHistory(any(), any());
            assertThat(queue.isActive()).isTrue();
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
        }

        @Test
        @DisplayName("보류 중 재조정: 직전 사이클의 앱 청산 건은 기록하지 않고 대기를 유지한다")
        void reconcile_waiting_staleOwnClose_keepsWaiting() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            state.setEntryTime(1_760_000_000_000L);
            state.setCloseDetailWaitStartedAt(System.currentTimeMillis());
            activeHoldingSession(queue, state);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(BigDecimal.ZERO);
            given(tradeFacade.findLastClose(any(), any(), anyLong(), any(), any()))
                    .willReturn(closeEvent(PositionCloseType.OTHER, "q1-L-1759999999999"));

            autoTradeService.reconcileExternalCloses();

            then(tradeFacade).should(never()).saveTradeHistory(any(), any());
            assertThat(queue.isActive()).isTrue();
            assertThat(state.getCloseDetailWaitStartedAt()).isNotNull();
        }

        @Test
        @DisplayName("청산 주문 실패 + 포지션 0 + 직전 사이클의 앱 청산 건: 기록하지 않고 대기로 전환한다")
        void holding_closeFails_staleOwnClose_startsWait() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            state.setEntryTime(1_760_000_000_000L);
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willThrow(new RuntimeException("주문 거부"));
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(BigDecimal.ZERO);
            given(tradeFacade.findLastClose(any(), any(), anyLong(), any(), any()))
                    .willReturn(closeEvent(PositionCloseType.OTHER, "q1-L-1759999999999"));

            autoTradeService.processPositionHolding(queue, state, session, "55500.00");

            then(tradeFacade).should(never()).saveTradeHistory(any(), any());
            assertThat(queue.isActive()).isTrue();
            assertThat(state.getCloseDetailWaitStartedAt()).isNotNull();
        }

        @ParameterizedTest
        @org.junit.jupiter.params.provider.CsvSource({"q1-S-1760000000000,SELL", "q1-L-1760000000000,LIQUIDATION"})
        @DisplayName("보류 중 재조정: 찾은 청산 건이 앱 주문이면 주문 의도대로 기록하고 큐를 끈다 (다음 진입 없음)")
        void reconcile_waiting_ownCloseFound_recordsByOrderAndDeactivates(String linkId, TradeOrderType expectedType) {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            state.setEntryTime(1_700_000_000_000L);
            state.setCloseDetailWaitStartedAt(System.currentTimeMillis());
            activeHoldingSession(queue, state);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(BigDecimal.ZERO);
            given(tradeFacade.findLastClose(any(), any(), anyLong(), any(), any()))
                    .willReturn(closeEvent(PositionCloseType.OTHER, linkId));

            autoTradeService.reconcileExternalCloses();

            ArgumentCaptor<TradeHistoryDTO> saved = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should().saveTradeHistory(saved.capture(), eq(TradeMode.MAIN));
            assertThat(saved.getValue().getOrderType()).isEqualTo(expectedType.getTradeOrderType());
            assertThat(saved.getValue().getOrderResult()).isEqualTo(OrderResult.SUCCESS);
            assertThat(saved.getValue().getExecutedPrice()).isEqualByComparingTo("55600");
            assertThat(queue.isActive()).isFalse();
            assertThat(deactivateAlertReason()).contains("앱 주문 응답 유실").doesNotContain("외부 청산");
            then(tradeFacade).should(never()).placeOrder(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("청산 주문 실패 + 포지션 0 + 내역이 앱 청산 주문이면 청산으로 기록하고 큐를 끈다")
        void closeFails_positionZero_ownCloseFound_recordsAndDeactivates() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willThrow(new RuntimeException("응답 유실"));
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(BigDecimal.ZERO);
            given(tradeFacade.findLastClose(any(), any(), anyLong(), any(), any()))
                    .willReturn(closeEvent(PositionCloseType.OTHER, "q1-L-1760000000000"));

            autoTradeService.processPositionHolding(queue, state, session, "44900.00");

            ArgumentCaptor<TradeHistoryDTO> saved = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should().saveTradeHistory(saved.capture(), eq(TradeMode.MAIN));
            assertThat(saved.getValue().getOrderType()).isEqualTo(TradeOrderType.LIQUIDATION.getTradeOrderType());
            assertThat(queue.isActive()).isFalse();
            assertThat(deactivateAlertReason()).contains("앱 주문 응답 유실");
        }

        @Test
        @DisplayName("보류 중 재조정: 포지션 조회에 실패하면 시작 시각을 유지한 채 일시 해제해 TP/SL 감시를 재개한다 (이력 없음)")
        void reconcile_waiting_positionLookupFails_suspendsWait() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            long startedAt = System.currentTimeMillis();
            state.setCloseDetailWaitStartedAt(startedAt);
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(null);

            autoTradeService.reconcileExternalCloses();

            assertThat(state.getCloseDetailWaitStartedAt()).isEqualTo(startedAt);
            assertThat(state.isCloseDetailWaitSuspended()).isTrue();
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
            then(tradeFacade).should(never()).findLastClose(any(), any(), anyLong(), any(), any());
            then(tradeFacade).should(never()).saveTradeHistory(any(), any());
            autoTradeService.processPositionHolding(queue, state, session, "55500.00");
            then(tradeFacade).should(atLeastOnce()).placeOrder(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("보류 중 재조정: 포지션이 남아 있으면 대기를 완전히 해제한다")
        void reconcile_waiting_positionOpen_releasesWait() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            state.setCloseDetailWaitStartedAt(System.currentTimeMillis());
            state.setCloseDetailWaitSuspended(true);
            activeHoldingSession(queue, state);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(new BigDecimal("0.001"));

            autoTradeService.reconcileExternalCloses();

            assertThat(state.getCloseDetailWaitStartedAt()).isNull();
            assertThat(state.isCloseDetailWaitSuspended()).isFalse();
            assertThat(queue.isActive()).isTrue();
        }

        @Test
        @DisplayName("일시 해제 후 포지션 0: 최초 시작 시각 기준 5분이 지났으면 새로 세지 않고 만료 처리한다")
        void reconcile_suspended_thenPositionZero_expiresFromFirstStart() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            state.setCloseDetailWaitStartedAt(System.currentTimeMillis() - 5 * 60 * 1000L - 1000L);
            activeHoldingSession(queue, state);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(null, BigDecimal.ZERO, BigDecimal.ZERO);
            given(tradeFacade.findLastClose(any(), any(), anyLong(), any(), any())).willReturn(null);

            autoTradeService.reconcileExternalCloses(); // 조회 실패 → 일시 해제
            autoTradeService.reconcileExternalCloses(); // 포지션 0 → 만료

            assertUnknownCloseRecorded();
            assertThat(queue.isActive()).isFalse();
            assertThat(deactivateAlertReason()).contains("청산 내역 확인 실패");
        }

        @Test
        @DisplayName("일시 해제 후 앱 주문 내역이 확인되면 버리지 않고 기록한 뒤 큐를 끈다")
        void reconcile_suspended_thenOwnCloseFound_recordsAndDeactivates() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            state.setEntryTime(1_700_000_000_000L);
            state.setCloseDetailWaitStartedAt(System.currentTimeMillis());
            activeHoldingSession(queue, state);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(null, BigDecimal.ZERO, BigDecimal.ZERO);
            given(tradeFacade.findLastClose(any(), any(), anyLong(), any(), any()))
                    .willReturn(closeEvent(PositionCloseType.OTHER, "q1-S-1760000000000"));

            autoTradeService.reconcileExternalCloses();
            autoTradeService.reconcileExternalCloses();

            ArgumentCaptor<TradeHistoryDTO> saved = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should().saveTradeHistory(saved.capture(), eq(TradeMode.MAIN));
            assertThat(saved.getValue().getOrderType()).isEqualTo(TradeOrderType.SELL.getTradeOrderType());
            assertThat(queue.isActive()).isFalse();
        }

        @Test
        @DisplayName("일시 해제 중 익절 지정가 보유 큐도 손절 감시를 재개한다 (지정가 취소 시도)")
        void holding_suspended_withTpLimit_resumesStopLoss() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitHoldingState();
            state.setActivePatternId(100L);
            state.setCloseDetailWaitStartedAt(System.currentTimeMillis());
            state.setCloseDetailWaitSuspended(true);
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            fakePositionSize.set(new BigDecimal("0.001"));
            given(tradeFacade.cancelOrder(any(), any(), any(), any(), any())).willReturn(OrderCancelResult.FAILED);

            autoTradeService.processPositionHolding(queue, state, session, "44900.00");

            then(tradeFacade).should().cancelOrder(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("일시 해제 + 지정가 보유: 손절 중 포지션 조회가 5회 실패해 꺼지면 STOP 이력을 남긴다")
        void holding_suspended_tpLimitLookupFails_recordsStopOnDeactivate() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitHoldingState();
            state.setActivePatternId(100L);
            state.setCloseDetailWaitStartedAt(System.currentTimeMillis());
            state.setCloseDetailWaitSuspended(true);
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            fakePositionSize.set(null);
            given(tradeFacade.cancelOrder(any(), any(), any(), any(), any())).willReturn(OrderCancelResult.CANCELLED);

            for (int i = 0; i < 5; i++) {
                state.setCloseConfirmCheckedAt(0L);
                autoTradeService.processPositionHolding(queue, state, session, "44900.00");
            }

            assertThat(queue.isActive()).isFalse();
            assertThat(stopHistories()).singleElement().satisfies(stop ->
                    assertThat(stop.getErrorMessage()).contains("청산 내역 미확인"));
            then(tradeFacade).should(never()).placeOrder(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("일시 해제 + 청산 주문 실패 + 재조회 실패로 꺼지면 STOP 이력을 남긴다")
        void holding_suspended_closeFailsLookupFails_recordsStopOnDeactivate() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            state.setCloseDetailWaitStartedAt(System.currentTimeMillis());
            state.setCloseDetailWaitSuspended(true);
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willThrow(new RuntimeException("주문 거부"));
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(new BigDecimal("0.001"), (BigDecimal) null);

            autoTradeService.processPositionHolding(queue, state, session, "55500.00");

            assertThat(queue.isActive()).isFalse();
            assertThat(stopHistories()).singleElement().satisfies(stop ->
                    assertThat(stop.getErrorMessage()).contains("청산 내역 미확인"));
        }

        @Test
        @DisplayName("대기 만료로 꺼지면 STOP 이력을 추가로 남기지 않는다")
        void reconcile_closeDetailExpired_noExtraStopHistory() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            state.setCloseDetailWaitStartedAt(System.currentTimeMillis() - 5 * 60 * 1000L - 1000L);
            activeHoldingSession(queue, state);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(BigDecimal.ZERO);
            given(tradeFacade.findLastClose(any(), any(), anyLong(), any(), any())).willReturn(null);

            autoTradeService.reconcileExternalCloses();

            assertThat(queue.isActive()).isFalse();
            assertThat(stopHistories()).isEmpty();
        }

        @Test
        @DisplayName("일시 해제 중 앱 청산이 확인되면 대기를 지우고, 큐 종료여도 미확인 STOP 이력을 남기지 않는다")
        void holding_suspended_ownCloseConfirmed_clearsWait() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            state.setCloseDetailWaitStartedAt(System.currentTimeMillis());
            state.setCloseDetailWaitSuspended(true);
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            fakePositionSize.set(new BigDecimal("0.001"));

            autoTradeService.processPositionHolding(queue, state, session, "55500.00");

            assertThat(state.getCloseDetailWaitStartedAt()).isNull();
            assertThat(state.isCloseDetailWaitSuspended()).isFalse();

            PatternQueue stopQueue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO stopState = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            stopState.setActivePatternId(100L);
            stopState.setCloseDetailWaitStartedAt(System.currentTimeMillis());
            stopState.setCloseDetailWaitSuspended(true);
            stopState.setStopRequested(true);
            AutoTradeSessionDTO stopSession = makeSession(1L, "BTCUSDT", stopQueue);
            stopSession.getQueueStates().put(stopQueue.getId(), stopState);
            fakePositionSize.set(new BigDecimal("0.001"));

            autoTradeService.processPositionHolding(stopQueue, stopState, stopSession, "55500.00");

            assertThat(stopQueue.isActive()).isFalse();
            assertThat(stopHistories()).isEmpty();
        }

        @Test
        @DisplayName("보류 중 재조정 명령에서 예외가 나면 [오류] 로그를 남기고 재조정 예외로 흘리지 않는다")
        void reconcile_waiting_commandThrows_logsError() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            state.setCloseDetailWaitStartedAt(System.currentTimeMillis());
            AutoTradeSessionDTO session = activeHoldingSession(queue, state);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(BigDecimal.ZERO).willThrow(new RuntimeException("조회 장애"));
            given(tradeFacade.findLastClose(any(), any(), anyLong(), any(), any())).willReturn(null);

            autoTradeService.reconcileExternalCloses();

            assertThat(session.getTradeLog()).anyMatch(l -> l.contains("[오류]") && l.contains("조회 장애"));
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
        }

        @Test
        @DisplayName("보류 중 재조정: 포지션 0 + 내역 조회 실패 + 대기 시간 안이면 계속 보류한다")
        void reconcile_waiting_detailLookupFails_withinLimit_keepsHolding() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            long startedAt = System.currentTimeMillis() - 4 * 60 * 1000L;
            state.setCloseDetailWaitStartedAt(startedAt);
            activeHoldingSession(queue, state);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(BigDecimal.ZERO);
            given(tradeFacade.findLastClose(any(), any(), anyLong(), any(), any())).willReturn(null);

            autoTradeService.reconcileExternalCloses();

            assertThat(queue.isActive()).isTrue();
            assertThat(state.getCloseDetailWaitStartedAt()).isEqualTo(startedAt);
            then(tradeFacade).should(never()).saveTradeHistory(any(), any());
        }

        @Test
        @DisplayName("보류 중 재조정: 포지션 0 + 내역 조회 실패 + 대기 시간 경과면 가격 0·실패 사유를 남기고 큐를 끈다")
        void reconcile_waiting_detailLookupFails_expired_recordsFailedAndDeactivates() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            state.setCloseDetailWaitStartedAt(System.currentTimeMillis() - 5 * 60 * 1000L - 1000L);
            activeHoldingSession(queue, state);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(BigDecimal.ZERO);
            given(tradeFacade.findLastClose(any(), any(), anyLong(), any(), any())).willReturn(null);

            autoTradeService.reconcileExternalCloses();

            assertUnknownCloseRecorded();
            assertThat(queue.isActive()).isFalse();
            assertThat(deactivateAlertReason()).contains("청산 내역 확인 실패");
        }

        @ParameterizedTest
        @EnumSource(value = PositionCloseType.class, names = {"TAKE_PROFIT", "STOP_LOSS", "LIQUIDATION"})
        @DisplayName("대기 시간 경과: 청산 방식이 확정돼도 체결가를 모르면 다음 거래로 넘어가지 않고 큐를 끈다")
        void reconcile_closeDetailExpired_knownType_deactivatesNoReentry(PositionCloseType closeType) {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            state.setCloseDetailWaitStartedAt(System.currentTimeMillis() - 5 * 60 * 1000L - 1000L);
            activeHoldingSession(queue, state);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(BigDecimal.ZERO);
            given(tradeFacade.findLastClose(any(), any(), anyLong(), any(), any()))
                    .willReturn(PositionCloseDTO.builder().userId(1L).exchangeType(ExchangeType.BYBIT)
                            .symbol("BTCUSDT").orderId("ext-1").closeType(closeType).build());

            autoTradeService.reconcileExternalCloses();

            assertThat(queue.isActive()).isFalse();
            assertThat(deactivateAlertReason()).contains("청산 내역 확인 실패");
            then(tradeFacade).should(never()).placeOrder(any(), any(), any(), any(), any());
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

        @Test
        @DisplayName("청산 접수로 안전망 취소 + 5회 연속 확인 실패로 큐가 꺼지면: 사유 앞에 안전망 취소를 알린다")
        void protectionCancelled_fiveFailures_disclosesCancelled() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG,
                    new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any()))
                    .willReturn(OrderResultDTO.builder().orderId("close-1").protectionCancelled(true).build());
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(new BigDecimal("0.001"));

            autoTradeService.processPositionHolding(queue, state, session, "55500.00"); // 1회
            for (int i = 0; i < 4; i++) {                                               // 2~5회
                state.setCloseConfirmCheckedAt(0L);
                autoTradeService.processPositionHolding(queue, state, session, "55500.00");
            }

            assertThat(queue.isActive()).isFalse();
            assertThat(deactivateAlertReason())
                    .startsWith("[거래소 안전망 취소됨 — 포지션이 남아 있으면 앱의 익절·손절도 멈춥니다] ");
        }

        @Test
        @DisplayName("청산 접수로 안전망 취소 + 포지션 0 확인: 포지션이 없으므로 표시를 해제한다")
        void protectionCancelled_closeConfirmed_clearsMark() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG,
                    new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any()))
                    .willReturn(OrderResultDTO.builder().orderId("close-1").protectionCancelled(true).build());
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(BigDecimal.ZERO);

            autoTradeService.processPositionHolding(queue, state, session, "55500.00");

            assertThat(queue.isActive()).isTrue();
            assertThat(state.getProtectionGap()).isEqualTo(ExchangeProtectionGap.NONE);
        }

        @Test
        @DisplayName("보호 주문 실패 표시 + 청산 주문 실패·포지션 잔존으로 큐가 꺼지면: 사유 앞 문구를 유지한다")
        void protectionMissing_closeOrderFails_positionRemains_keepsNote() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG,
                    new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            state.setProtectionGap(ExchangeProtectionGap.ALL);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willThrow(new RuntimeException("청산 거부"));
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(new BigDecimal("0.001"));

            autoTradeService.processPositionHolding(queue, state, session, "55500.00");

            assertThat(queue.isActive()).isFalse();
            assertThat(deactivateAlertReason()).startsWith("[거래소 안전망 미설치 — 앱의 익절·손절도 멈춥니다] ");
        }

        private static final String CANCELLED_NOTE = "[거래소 안전망 취소됨 — 포지션이 남아 있으면 앱의 익절·손절도 멈춥니다] ";
        private static final String MISSING_NOTE = "[거래소 안전망 미설치 — 앱의 익절·손절도 멈춥니다] ";

        @Test
        @DisplayName("청산 접수로 안전망 취소 + 5회 연속 확인 실패로 큐가 꺼지면: STOP 이력에 문구와 사유를 남긴다")
        void protectionCancelled_fiveFailures_recordsStop() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG,
                    new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(100L);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any()))
                    .willReturn(OrderResultDTO.builder().orderId("close-1").protectionCancelled(true).build());
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(new BigDecimal("0.001"));

            autoTradeService.processPositionHolding(queue, state, session, "55500.00"); // 1회
            for (int i = 0; i < 4; i++) {                                               // 2~5회
                state.setCloseConfirmCheckedAt(0L);
                autoTradeService.processPositionHolding(queue, state, session, "55500.00");
            }

            assertThat(queue.isActive()).isFalse();
            then(tradeFacade).should(atLeastOnce()).saveTradeHistory(any(), eq(TradeMode.MAIN));
            List<TradeHistoryDTO> stops = stopHistories();
            assertThat(stops).hasSize(1); // 중복 기록 없음
            TradeHistoryDTO stop = stops.get(0);
            assertThat(stop.getErrorMessage()).startsWith(CANCELLED_NOTE).contains("청산 완료 확인 실패");
            assertThat(stop.getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(stop.getSide()).isEqualTo(Side.LONG);
            assertThat(stop.getPatternId()).isEqualTo(100L);
            assertThat(stop.getSymbol()).isEqualTo("BTCUSDT");
        }

        /** 보호 공백(ALL)이 있는 보유 상태에서 청산 주문이 거부되고 포지션이 남는 상황을 만든다 */
        private AutoTradeSessionDTO closeOrderFailsWithGap(PatternQueue queue, QueueStateDTO state) {
            state.setActivePatternId(100L);
            state.setProtectionGap(ExchangeProtectionGap.ALL);
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willThrow(new RuntimeException("청산 거부"));
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(new BigDecimal("0.001"));
            return makeSession(1L, "BTCUSDT", queue);
        }

        @Test
        @DisplayName("보호 공백 + 청산 주문 실패·포지션 잔존으로 큐가 꺼지면: STOP 이력에 문구와 사유를 남긴다")
        void protectionMissing_closeOrderFails_recordsStop() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            AutoTradeSessionDTO session = closeOrderFailsWithGap(queue, state);

            autoTradeService.processPositionHolding(queue, state, session, "55500.00");

            assertThat(queue.isActive()).isFalse();
            assertThat(stopHistories()).singleElement().satisfies(stop ->
                    assertThat(stop.getErrorMessage()).startsWith(MISSING_NOTE).contains("매도 실패"));
        }

        @Test
        @DisplayName("STOP 이력 저장 실패: 큐 끄기와 알림은 그대로 진행한다")
        void stopHistorySaveFails_stillDeactivatesAndAlerts() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            AutoTradeSessionDTO session = closeOrderFailsWithGap(queue, state);
            willThrow(new RuntimeException("DB 오류")).given(tradeFacade).saveTradeHistory(any(), any());

            autoTradeService.processPositionHolding(queue, state, session, "55500.00");

            assertThat(queue.isActive()).isFalse();
            assertThat(deactivateAlertReason()).startsWith(MISSING_NOTE);
        }

        @Test
        @DisplayName("STOP 이력용 시세 조회 예외: 가격 0으로 STOP 이력을 남기고 큐 끄기와 알림을 진행한다")
        void stopHistoryTickerThrows_stillDeactivatesAndAlerts() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            AutoTradeSessionDTO session = closeOrderFailsWithGap(queue, state);
            given(marketService.getTicker(ExchangeType.BYBIT, "BTCUSDT")).willThrow(new RuntimeException("시세 오류"));

            autoTradeService.processPositionHolding(queue, state, session, "55500.00");

            assertThat(queue.isActive()).isFalse();
            assertThat(deactivateAlertReason()).startsWith(MISSING_NOTE);
            assertThat(stopHistories()).singleElement().satisfies(stop -> {
                assertThat(stop.getErrorMessage()).startsWith(MISSING_NOTE);
                assertThat(stop.getExecutedPrice()).isEqualByComparingTo(BigDecimal.ZERO);
            });
        }

        @Test
        @DisplayName("STOP 이력용 시세가 숫자가 아니면: 가격 0으로 STOP 이력을 남기고 큐 끄기와 알림을 진행한다")
        void stopHistoryPriceInvalid_stillDeactivatesAndAlerts() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            AutoTradeSessionDTO session = closeOrderFailsWithGap(queue, state);
            given(marketService.getTicker(ExchangeType.BYBIT, "BTCUSDT"))
                    .willReturn(TickerDTO.builder().lastPrice("").build());

            autoTradeService.processPositionHolding(queue, state, session, "55500.00");

            assertThat(queue.isActive()).isFalse();
            assertThat(deactivateAlertReason()).startsWith(MISSING_NOTE);
            assertThat(stopHistories()).singleElement().satisfies(stop -> {
                assertThat(stop.getErrorMessage()).startsWith(MISSING_NOTE);
                assertThat(stop.getExecutedPrice()).isEqualByComparingTo(BigDecimal.ZERO);
            });
        }

        /** 익절 지정가 취소 뒤 포지션 0으로 손절을 생략하는 상황 (두 번째 조회 결과를 지정) */
        private void stopLossWithZeroPosition(BigDecimal secondLookup) {
            PatternQueue queue = makeTwoStepQueue(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitHoldingState();
            state.setProtectionGap(ExchangeProtectionGap.ALL);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            given(tradeFacade.cancelOrder(1L, "BTCUSDT", "q1-T-1", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(OrderCancelResult.CANCELLED);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(BigDecimal.ZERO).willReturn(secondLookup);

            autoTradeService.processPositionHolding(queue, state, session, "44000.00");

            assertThat(queue.isActive()).isFalse();
        }

        @Test
        @DisplayName("손절 전 포지션 0 확인 + 재조회 null로 큐가 꺼지면: 문구와 STOP 이력을 남기지 않는다")
        void stopLoss_zeroThenNull_noNoteNoStop() {
            stopLossWithZeroPosition(null);

            assertThat(deactivateAlertReason()).doesNotContain("거래소 안전망");
            assertThat(stopHistories()).isEmpty();
        }

        @Test
        @DisplayName("손절 전 포지션 0 확인 + 재조회에서 포지션 발견으로 큐가 꺼지면: 문구와 STOP 이력을 유지한다")
        void stopLoss_zeroThenPositionFound_keepsNoteAndStop() {
            stopLossWithZeroPosition(new BigDecimal("0.001"));

            assertThat(deactivateAlertReason()).startsWith(MISSING_NOTE);
            assertThat(stopHistories()).singleElement().satisfies(stop ->
                    assertThat(stop.getErrorMessage()).startsWith(MISSING_NOTE));
        }

        @Test
        @DisplayName("큐 종료 전 포지션 0 확인 + 재조회 null로 큐가 꺼지면: 문구와 STOP 이력을 남기지 않는다")
        void closeQueue_zeroThenNull_noNoteNoStop() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitState();
            state.setProtectionGap(ExchangeProtectionGap.ALL);
            activeHoldingSession(queue, state);
            given(marketService.getTicker(ExchangeType.BYBIT, "BTCUSDT"))
                    .willReturn(TickerDTO.builder().lastPrice("51000").build());
            given(tradeFacade.cancelOrder(1L, "BTCUSDT", "q1-T-1", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(OrderCancelResult.CANCELLED);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN))
                    .willReturn(BigDecimal.ZERO).willReturn((BigDecimal) null);

            autoTradeService.closeQueuePosition(1L, 1L);

            assertThat(deactivateAlertReason()).doesNotContain("거래소 안전망");
            assertThat(stopHistories()).isEmpty();
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
        @DisplayName("부분 익절 값이 남은 채 포지션이 끝나면 다음 사이클로 넘기지 않는다 (매도 초기화·청산 후 다음 단계)")
        void partialTpLeftover_clearedOnPositionEnd() {
            PatternQueue queue = makeTwoStepQueue(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitHoldingState();
            state.setActivePatternId(100L);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            session.getQueueStates().put(1L, state);

            state.setPartialTpQty("0.0009");
            ReflectionTestUtils.invokeMethod(autoTradeService, "resetToFirstStepAfterSell", queue, state, session);
            assertThat(state.getPartialTpQty()).isNull();

            state.setPartialTpQty("0.0009");
            ReflectionTestUtils.invokeMethod(autoTradeService, "moveToNextStepAfterLiquidation", queue, state, session, Side.SHORT);
            assertThat(state.getPartialTpQty()).isNull();
        }

        @Test
        @DisplayName("외부 청산 이력을 남기지 못해도 부분 익절 값은 비운다")
        void externalClose_noPattern_partialTpCleared() {
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG, new BigDecimal("55000"), new BigDecimal("45000"));
            state.setActivePatternId(999L); // 없는 패턴 → 이력 생략 경로
            state.setPartialTpQty("0.0009");
            state.setPartialTpPrice(new BigDecimal("55000"));
            state.setPartialTpFee(BigDecimal.ONE);
            state.setPartialTpOrderId("tp-order-1"); // ID가 있어야 잔여 내역으로 인정되어 기록 경로로 진입
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            session.getQueueStates().put(1L, state);
            given(tradeFacade.getPositionSize(1L, "BTCUSDT", ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(BigDecimal.ZERO);

            ReflectionTestUtils.invokeMethod(autoTradeService, "processExternalClose", session,
                    closeEvent(PositionCloseType.TAKE_PROFIT, null));

            assertThat(state.getPartialTpQty()).isNull();
        }

        @Test
        @DisplayName("부분 익절 후 잔여 청산 응답 유실: 잔여 수량·비례 마진으로 기록하고 체결가를 합산한다")
        void stopLoss_partialTp_closeResponseLost_recordsRemainingOnly() {
            PatternQueue queue = makeTwoStepQueue(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = tpLimitHoldingState();
            state.setCurrentStepLevel(2);
            state.setActivePatternId(100L);
            state.setEntryTime(1_700_000_000_000L);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            session.getQueueStates().put(1L, state);
            // 0.0009 익절 체결 후 잔여 0.0001 청산 → 응답은 유실됐지만 거래소에서는 체결됨
            fakePositionSize.set(new BigDecimal("0.0001"));
            given(tradeFacade.cancelOrder(any(), any(), any(), any(), any())).willReturn(OrderCancelResult.CANCELLED);
            given(tradeFacade.placeOrder(any(), any(), any(), any(), any())).willAnswer(inv -> {
                fakePositionSize.set(BigDecimal.ZERO);
                throw new RuntimeException("응답 유실");
            });
            given(tradeFacade.findLastClose(any(), any(), anyLong(), any(), any())).willReturn(PositionCloseDTO.builder()
                    .userId(1L).exchangeType(ExchangeType.BYBIT).symbol("BTCUSDT")
                    .orderId("lost-1").orderLinkId("q1-L-" + (System.currentTimeMillis() + 60_000L)).execPrice("45000")
                    .closeType(PositionCloseType.OTHER).build());

            autoTradeService.processPositionHolding(queue, state, session, "45000.00");

            ArgumentCaptor<TradeHistoryDTO> histories = ArgumentCaptor.forClass(TradeHistoryDTO.class);
            then(tradeFacade).should(atLeast(1)).saveTradeHistory(histories.capture(), eq(TradeMode.MAIN));
            TradeHistoryDTO lost = histories.getAllValues().stream()
                    .filter(h -> TradeOrderType.LIQUIDATION.getTradeOrderType().equals(h.getOrderType())
                            && OrderResult.SUCCESS.equals(h.getOrderResult())).findFirst().orElseThrow();
            // 잔여 0.0001 / 진입 0.001 = 마진의 10%
            assertThat(lost.getAmount()).isEqualByComparingTo("10");
            // 투자 이력 청산가 = (55000×0.0009 + 45000×0.0001) / 0.001 = 54000
            ArgumentCaptor<InvestmentHistoryDTO> invest = ArgumentCaptor.forClass(InvestmentHistoryDTO.class);
            then(tradeFacade).should().saveInvestmentHistory(invest.capture(), any());
            assertThat(invest.getValue().getExitPrice()).isEqualByComparingTo("54000");
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
        @DisplayName("오토스탑 큐 POSITION_HOLDING 시 진입 시점 거래량(entryVolume)을 반환한다")
        void returnsEntryVolumeOnPositionHolding() {
            Long userId = 1L;
            String symbol = "BTCUSDT";
            PatternQueue queue = makePatternQueue(1L, userId, symbol, Side.LONG);
            queue.setVolumeStopEnabled(true);
            given(patternQueueRepository.findByUserIdAndSymbolAndIsActiveAndTradeModeAndExchangeTypeOrderByCreatedAtAsc(
                    userId, symbol, true, TradeMode.MAIN, ExchangeType.BYBIT)).willReturn(List.of(queue));
            autoTradeService.syncSession(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN);

            var state = autoTradeService.getSession(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN).getQueueStates().get(1L);
            state.setPhase(TradePhase.POSITION_HOLDING);
            state.setDirection(Side.LONG);
            state.setEntryPrice("50000.00");
            state.setActivePatternId(10L);
            state.setVolumeBaseline(12.5); // 진입 시점 24시간 거래량

            TickerDTO wsTicker = TickerDTO.builder().lastPrice("52500.00").build();
            given(marketService.getTicker(ExchangeType.BYBIT, symbol)).willReturn(wsTicker);

            var response = autoTradeService.getStatusResponse(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN);

            assertThat(response.getEntryVolume()).isEqualTo(12.5);
        }

        @Test
        @DisplayName("진입 시점 거래량이 아직 확정 전(시세 미수신)이면 entryVolume은 null이다")
        void returnsNullEntryVolumeWhileWarmingUp() {
            Long userId = 1L;
            String symbol = "BTCUSDT";
            PatternQueue queue = makePatternQueue(1L, userId, symbol, Side.LONG);
            queue.setVolumeStopEnabled(true);
            given(patternQueueRepository.findByUserIdAndSymbolAndIsActiveAndTradeModeAndExchangeTypeOrderByCreatedAtAsc(
                    userId, symbol, true, TradeMode.MAIN, ExchangeType.BYBIT)).willReturn(List.of(queue));
            autoTradeService.syncSession(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN);

            var state = autoTradeService.getSession(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN).getQueueStates().get(1L);
            state.setPhase(TradePhase.POSITION_HOLDING);
            state.setDirection(Side.LONG);
            state.setEntryPrice("50000.00");
            state.setActivePatternId(10L);
            state.setVolumeBaseline(null); // 시세 미수신으로 미확정

            TickerDTO wsTicker = TickerDTO.builder().lastPrice("52500.00").build();
            given(marketService.getTicker(ExchangeType.BYBIT, symbol)).willReturn(wsTicker);

            var response = autoTradeService.getStatusResponse(userId, ExchangeType.BYBIT, symbol, TradeMode.MAIN);

            assertThat(response.getEntryVolume()).isNull();
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

        /** 대시보드와 같은 시세의 24시간 거래량을 모의한다. (null = 시세 미수신) */
        private void givenVolume24h(String volume24h) {
            given(marketService.getTicker(ExchangeType.BYBIT, "BTCUSDT")).willReturn(volume24h == null ? null
                    : TickerDTO.builder().symbol("BTCUSDT").lastPrice("51000").volume24h(volume24h).build());
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
            givenVolume24h("40");

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
        @DisplayName("[사용자 정책 2026-10-31] 거래량이 진입 기준의 1.5배 이상으로 늘어도 예약하지 않는다 (감소만 판정)")
        void surge_noReservation() {
            for (String volume : new String[]{"150", "300"}) {
                PatternQueue queue = enabledQueue();
                QueueStateDTO state = holding(Side.LONG, 100.0);
                AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
                givenVolume24h(volume);

                autoTradeService.processPositionHolding(queue, state, session, "51000.00");

                assertThat(queue.getVolumeStopReason()).isNull();
            }
            then(patternQueueRepository).should(never()).updateVolumeStopReason(any(), any());
        }

        @Test
        @DisplayName("0.5배 초과면 예약하지 않는다")
        void withinRange_noReservation() {
            PatternQueue queue = enabledQueue();
            QueueStateDTO state = holding(Side.LONG, 100.0);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            givenVolume24h("149");

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

            then(marketService).should(never()).getTicker(any(), any());
            then(patternQueueRepository).should(never()).updateVolumeStopReason(any(), any());
        }

        @Test
        @DisplayName("진입 시 시세 미수신으로 기준이 없으면 처음 받은 24시간 거래량을 기준으로 확정하고 판정하지 않는다")
        void warmup_fixesBaselineWhenWindowFull() {
            PatternQueue queue = enabledQueue();
            QueueStateDTO state = holding(Side.LONG, null);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            // 시세 미수신 → 기준 미확정
            givenVolume24h(null);
            autoTradeService.processPositionHolding(queue, state, session, "51000.00");
            assertThat(state.getVolumeBaseline()).isNull();

            // 시세 수신 → 그 값으로 기준 확정
            givenVolume24h("80");
            autoTradeService.processPositionHolding(queue, state, session, "51000.00");
            assertThat(state.getVolumeBaseline()).isEqualTo(80.0);
            then(patternQueueRepository).should(never()).updateVolumeStopReason(any(), any());
        }

        @Test
        @DisplayName("진입 시 그 시점의 24시간 거래량(대시보드 값)을 기준으로 저장한다")
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
            givenVolume24h("120");

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

        /** 거래량 중지 예약 + 청산 확인 뒤 큐가 꺼지고, 사유가 담긴 STOP 이력 1건이 가격 0으로 남는지 확인한다 */
        private void assertReservedStopWithZeroPrice() {
            PatternQueue queue = enabledQueue();
            queue.setVolumeStopReason(RESERVED);
            QueueStateDTO state = holding(Side.LONG, 100.0);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            session.getQueueStates().put(1L, state);

            autoTradeService.processPositionHolding(queue, state, session, "55500.00");

            assertThat(queue.isActive()).isFalse();
            assertThat(queue.getVolumeStopReason()).isNull();
            // 세션에서 빠져야 다음 틱이 이 큐를 처리하지 않아 반복 청산이 나가지 않는다
            assertThat(session.getQueues()).isEmpty();
            assertThat(session.getQueueStates()).doesNotContainKey(1L);
            then(tradeFacade).should(times(1)).placeOrder(any(), any(), any(), any(), any());
            assertThat(stopHistories()).singleElement().satisfies(stop -> {
                assertThat(stop.getErrorMessage()).isEqualTo(RESERVED);
                assertThat(stop.getExecutedPrice()).isEqualByComparingTo(BigDecimal.ZERO);
            });
        }

        @Test
        @DisplayName("예약 중지 시 시세가 숫자가 아니면: 가격 0으로 STOP 이력을 남기고 큐를 끈다")
        void reserved_tickerPriceInvalid_stillStops() {
            given(marketService.getTicker(ExchangeType.BYBIT, "BTCUSDT"))
                    .willReturn(TickerDTO.builder().lastPrice("").build());

            assertReservedStopWithZeroPrice();
        }

        @Test
        @DisplayName("예약 중지 시 시세 조회가 실패하면: 가격 0으로 STOP 이력을 남기고 큐를 끈다")
        void reserved_tickerThrows_stillStops() {
            given(marketService.getTicker(ExchangeType.BYBIT, "BTCUSDT")).willThrow(new RuntimeException("시세 오류"));

            assertReservedStopWithZeroPrice();
        }

        @Test
        @DisplayName("안전망 취소 표시가 있어도 청산 확인 뒤 예약 중지되면: STOP 이력은 거래량 사유 1건이다")
        void reserved_protectionCancelled_closeConfirmed_singleStop() {
            PatternQueue queue = enabledQueue();
            queue.setVolumeStopReason(RESERVED);
            QueueStateDTO state = holding(Side.LONG, 100.0);
            state.setProtectionGap(ExchangeProtectionGap.CANCELLED);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);
            session.getQueueStates().put(1L, state);

            autoTradeService.processPositionHolding(queue, state, session, "55500.00");

            assertThat(queue.isActive()).isFalse();
            assertThat(stopHistories()).singleElement().satisfies(stop ->
                    assertThat(stop.getErrorMessage()).isEqualTo(RESERVED));
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
            state.setProtectionGap(ExchangeProtectionGap.ALL);
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
            assertThat(state.getProtectionGap()).isEqualTo(ExchangeProtectionGap.NONE);
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
