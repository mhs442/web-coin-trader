package com.coin.webcointrader.autotrade.service;

import com.coin.webcointrader.autotrade.dto.AutoTradeSessionDTO;
import com.coin.webcointrader.autotrade.dto.AutoTradeStatusResponse;
import com.coin.webcointrader.autotrade.dto.QueueStateDTO;
import com.coin.webcointrader.autotrade.dto.TradePhase;
import com.coin.webcointrader.autotrade.repository.InvestmentHistoryRepository;
import com.coin.webcointrader.autotrade.repository.PatternQueueRepository;
import com.coin.webcointrader.autotrade.repository.TradeHistoryRepository;
import com.coin.webcointrader.common.client.market.BybitWebSocketClient;
import com.coin.webcointrader.common.client.market.dto.WebSocketKlineDTO;
import com.coin.webcointrader.common.dto.request.CreateOrderRequest;
import com.coin.webcointrader.common.dto.response.FindTickerResponse;
import com.coin.webcointrader.common.entity.*;
import com.coin.webcointrader.common.enums.TradeMode;
import com.coin.webcointrader.market.service.MarketService;
import com.coin.webcointrader.trade.service.TradeFacade;
import com.coin.webcointrader.trade.service.TradeService;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;

import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class AutoTradeServiceTest {

    @InjectMocks
    private AutoTradeService autoTradeService;

    @Mock
    private PatternQueueRepository patternQueueRepository;

    @Mock
    private TradeHistoryRepository tradeHistoryRepository;

    @Mock
    private InvestmentHistoryRepository investmentHistoryRepository;

    @Mock
    private TradeFacade tradeFacade;

    @Mock
    private TradeService tradeService;

    @Mock
    private MarketService marketService;

    @Mock
    private BybitWebSocketClient bybitWebSocketClient;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    // ─────────────────────────────────────────────
    // init (가격 리스너 등록)
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("init: 시작 시 MarketService에 가격 리스너를 등록한다")
    void init_registersPriceListener() {
        autoTradeService.init();
        then(marketService).should(times(1)).addPriceListener(any());
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
        given(patternQueueRepository.findByUserIdAndSymbolAndIsActiveAndTradeModeOrderByCreatedAtAsc(
                userId, symbol, true, TradeMode.MAIN)).willReturn(List.of(q));

        autoTradeService.syncSession(userId, symbol, TradeMode.MAIN);

        assertThat(autoTradeService.isActive(userId, symbol, TradeMode.MAIN)).isTrue();
        AutoTradeSessionDTO session = autoTradeService.getSession(userId, symbol, TradeMode.MAIN);
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
        given(patternQueueRepository.findByUserIdAndSymbolAndIsActiveAndTradeModeOrderByCreatedAtAsc(
                userId, symbol, true, TradeMode.MAIN)).willReturn(List.of(q1));

        autoTradeService.syncSession(userId, symbol, TradeMode.MAIN);

        PatternQueue q2 = makePatternQueue(2L, userId, symbol, Side.SHORT);
        given(patternQueueRepository.findByUserIdAndSymbolAndIsActiveAndTradeModeOrderByCreatedAtAsc(
                userId, symbol, true, TradeMode.MAIN)).willReturn(List.of(q1, q2));

        autoTradeService.syncSession(userId, symbol, TradeMode.MAIN);

        assertThat(autoTradeService.getSession(userId, symbol, TradeMode.MAIN).getQueues()).hasSize(2);
        assertThat(autoTradeService.getSession(userId, symbol, TradeMode.MAIN).getQueueStates()).hasSize(2);
    }

    @Test
    @DisplayName("syncSession: 활성 큐가 없으면 세션을 제거한다")
    void syncSession_removesSessionWhenNoActiveQueues() {
        Long userId = 1L;
        String symbol = "BTCUSDT";
        PatternQueue q = makePatternQueue(1L, userId, symbol, Side.LONG);

        given(patternQueueRepository.findByUserIdAndSymbolAndIsActiveAndTradeModeOrderByCreatedAtAsc(
                userId, symbol, true, TradeMode.MAIN)).willReturn(List.of(q));
        autoTradeService.syncSession(userId, symbol, TradeMode.MAIN);

        given(patternQueueRepository.findByUserIdAndSymbolAndIsActiveAndTradeModeOrderByCreatedAtAsc(
                userId, symbol, true, TradeMode.MAIN)).willReturn(List.of());

        autoTradeService.syncSession(userId, symbol, TradeMode.MAIN);
        assertThat(autoTradeService.isActive(userId, symbol, TradeMode.MAIN)).isFalse();
    }

    @Test
    @DisplayName("syncSession: WebSocket 구독을 동기화한다")
    void syncSession_syncsWebSocketSubscriptions() {
        Long userId = 1L;
        String symbol = "BTCUSDT";
        PatternQueue q = makePatternQueue(1L, userId, symbol, Side.LONG);
        given(patternQueueRepository.findByUserIdAndSymbolAndIsActiveAndTradeModeOrderByCreatedAtAsc(
                userId, symbol, true, TradeMode.MAIN)).willReturn(List.of(q));

        autoTradeService.syncSession(userId, symbol, TradeMode.MAIN);

        ArgumentCaptor<Set<String>> captor = ArgumentCaptor.forClass(Set.class);
        then(bybitWebSocketClient).should(atLeastOnce()).syncSubscriptions(captor.capture());
        assertThat(captor.getValue()).contains("BTCUSDT");
    }

    // ─────────────────────────────────────────────
    // isActive / getSession
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("isActive: 세션이 없으면 false를 반환한다")
    void isActive_returnsFalseWhenNoSession() {
        assertThat(autoTradeService.isActive(1L, "BTCUSDT", TradeMode.MAIN)).isFalse();
    }

    @Test
    @DisplayName("getSession: 세션이 없으면 null을 반환한다")
    void getSession_returnsNullWhenNotFound() {
        assertThat(autoTradeService.getSession(1L, "BTCUSDT", TradeMode.MAIN)).isNull();
    }

    // ─────────────────────────────────────────────
    // tick (fallback 스케줄러)
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("tick: 활성 세션이 없으면 marketService를 호출하지 않는다")
    void tick_doesNothingWhenNoSessions() {
        autoTradeService.tick();
        then(marketService).should(never()).getTickers();
    }

    @Test
    @DisplayName("tick: getTickers 응답이 null이면 처리를 중단한다")
    void tick_stopsWhenTickersNull() {
        Long userId = 1L;
        String symbol = "BTCUSDT";
        PatternQueue q = makePatternQueue(1L, userId, symbol, Side.LONG);
        given(patternQueueRepository.findByUserIdAndSymbolAndIsActiveAndTradeModeOrderByCreatedAtAsc(
                userId, symbol, true, TradeMode.MAIN)).willReturn(List.of(q));
        autoTradeService.syncSession(userId, symbol, TradeMode.MAIN);
        given(marketService.getTickers()).willReturn(null);

        assertThatCode(() -> autoTradeService.tick()).doesNotThrowAnyException();
        then(tradeFacade).should(never()).placeOrder(any(), any(), any(), any());
    }

    // ─────────────────────────────────────────────
    // onPriceUpdate (WebSocket 이벤트 핸들러)
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("onPriceUpdate: 활성 세션이 없으면 아무 작업도 하지 않는다")
    void onPriceUpdate_doesNothingWhenNoSessions() {
        assertThatCode(() -> autoTradeService.onPriceUpdate("BTCUSDT", "50000.00"))
                .doesNotThrowAnyException();
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
            PatternStep step = makeStepWithBothPatterns(1);

            Pattern result = autoTradeService.selectPattern(step, Side.LONG);

            assertThat(result).isNotNull();
            // 첫 번째 조건 블록이 LONG인 패턴
            PatternBlock firstBlock = result.getBlocks().stream()
                    .filter(b -> !b.isLeaf())
                    .min(java.util.Comparator.comparingInt(PatternBlock::getBlockOrder))
                    .orElse(null);
            assertThat(firstBlock).isNotNull();
            assertThat(firstBlock.getSide()).isEqualTo(Side.LONG);
        }

        @Test
        @DisplayName("SHORT 방향이면 첫 블록이 SHORT인 패턴을 선택한다")
        void selectsShortPattern() {
            PatternStep step = makeStepWithBothPatterns(1);

            Pattern result = autoTradeService.selectPattern(step, Side.SHORT);

            assertThat(result).isNotNull();
            PatternBlock firstBlock = result.getBlocks().stream()
                    .filter(b -> !b.isLeaf())
                    .min(java.util.Comparator.comparingInt(PatternBlock::getBlockOrder))
                    .orElse(null);
            assertThat(firstBlock).isNotNull();
            assertThat(firstBlock.getSide()).isEqualTo(Side.SHORT);
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
            state.setActiveStepId(10L);
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

        private WebSocketKlineDTO.KlineData makeKline(long startMs, String open, String close) {
            WebSocketKlineDTO.KlineData k = new WebSocketKlineDTO.KlineData();
            k.setStart(startMs);
            k.setEnd(startMs + 59_999); // 1분봉의 마지막 ms
            k.setOpen(open);
            k.setClose(close);
            k.setConfirm(true);
            return k;
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
            autoTradeService.onKlineConfirmed("BTCUSDT", makeKline(startMs, "50000", "55000"));

            QueueStateDTO state = session.getQueueStates().get(queue.getId());
            assertThat(state.getCurrentBlockOrder()).isEqualTo(2);
            assertThat(state.getPhase()).isEqualTo(TradePhase.BLOCK_MATCHING);
        }

        @Test
        @DisplayName("음봉(close < open)인데 조건 블록이 LONG이면 다음 단계로 이동한다 (포지션 미보유 → 청산X)")
        void movesToNextStepOnOppositeCandleSignal() {
            // 2단계 큐 (1단계: LONG, 2단계: LONG/SHORT 양쪽)
            PatternQueue queue = makeTwoStepQueue(1L, 1L, "BTCUSDT", 10);
            long startMs = System.currentTimeMillis() - 60_000;
            AutoTradeSessionDTO session = setupSessionWithCondBlock(queue, startMs + 5_000);

            // 음봉 (50000 → 45000) → SHORT 신호 → 1단계 패턴 첫 블록(L)과 반대
            autoTradeService.onKlineConfirmed("BTCUSDT", makeKline(startMs, "50000", "45000"));

            QueueStateDTO state = session.getQueueStates().get(queue.getId());
            assertThat(state.getCurrentStepLevel()).isEqualTo(2);
            assertThat(state.getDirection()).isEqualTo(Side.SHORT);
            assertThat(state.getCurrentBlockOrder()).isEqualTo(2); // 첫 블록 자동 매칭
            then(tradeFacade).should(never()).placeOrder(any(), any(), any(), any());
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
            autoTradeService.onKlineConfirmed("BTCUSDT", makeKline(startMs, "50000", "50000"));

            assertThat(state.getCurrentBlockOrder()).isEqualTo(1); // 변화 없음
            assertThat(state.getBlockBaseTime()).isAfter(origBaseTime); // 기준 시각 리셋
        }

        @Test
        @DisplayName("blockBaseTime이 봉 구간 밖이면 신호를 무시한다")
        void ignoresWhenBlockBaseOutsideCandle() {
            PatternQueue queue = makeThreeBlockLongQueue(10);
            long startMs = System.currentTimeMillis() - 60_000;
            // blockBaseTime이 봉 시작 이전(=다른 봉)
            AutoTradeSessionDTO session = setupSessionWithCondBlock(queue, startMs - 10_000);
            QueueStateDTO state = session.getQueueStates().get(queue.getId());

            autoTradeService.onKlineConfirmed("BTCUSDT", makeKline(startMs, "50000", "55000"));

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

            autoTradeService.onKlineConfirmed("BTCUSDT", makeKline(startMs, "50000", "55000"));

            // leaf는 봉 마감 신호로 트리거되지 않음 (가격 push의 즉시 진입에서 처리)
            assertThat(state.getCurrentBlockOrder()).isEqualTo(3);
            then(tradeFacade).should(never()).placeOrder(any(), any(), any(), any());
        }
    }

    // ─────────────────────────────────────────────
    // 포지션 진입 (processPositionOpen)
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
            state.setActiveStepId(10L);
            state.setActivePatternId(10L);
            state.setCurrentBlockOrder(2); // leaf 위치
            return state;
        }

        @Test
        @DisplayName("진입 시 notional(amount × leverage)로 convertUsdtToQty를 호출한다")
        void callsConvertUsdtToQtyWithNotional() {
            // amount=100, leverage=10 → notional=1000
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            ArgumentCaptor<BigDecimal> notionalCaptor = ArgumentCaptor.forClass(BigDecimal.class);
            given(marketService.convertUsdtToQty(eq("BTCUSDT"), notionalCaptor.capture(), any()))
                    .willReturn("0.002");

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            // amount(100) × leverage(10) = 1000 USDT 로 호출되어야 함
            assertThat(notionalCaptor.getValue()).isEqualByComparingTo(new BigDecimal("1000"));
        }

        @Test
        @DisplayName("진입 성공 시 entryQty/entryMargin을 저장하고 POSITION_HOLDING 페이즈로 전환한다")
        void savesEntryQtyAndTransitionsToPositionHolding() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            given(marketService.convertUsdtToQty(any(), any(), any())).willReturn("0.002");

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            assertThat(state.getEntryQty()).isEqualTo("0.002");
            assertThat(state.getEntryMargin()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(state.getEntryPrice()).isEqualTo("50000.00");
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
        }

        @Test
        @DisplayName("진입 성공 시 closeSkipCount를 0으로 초기화한다")
        void resetsCloseSkipCountOnSuccess() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            state.setCloseSkipCount(3); // 이전에 스킵이 있었던 상태 가정
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            given(marketService.convertUsdtToQty(any(), any(), any())).willReturn("0.002");

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            assertThat(state.getCloseSkipCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("LONG 진입 시 leverage 기반으로 TP/SL 가격을 계산한다 (default 100/leverage)")
        void calculatesTpSlOnLongEntry() {
            // leverage=10 → 기본 TP/SL = 10% → entry=50000, tp=55000, sl=45000
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            given(marketService.convertUsdtToQty(any(), any(), any())).willReturn("0.002");

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            assertThat(state.getTpPrice()).isEqualByComparingTo(new BigDecimal("55000.00"));
            assertThat(state.getSlPrice()).isEqualByComparingTo(new BigDecimal("45000.00"));
        }

        @Test
        @DisplayName("SHORT 진입 시 TP는 진입가 미만, SL은 진입가 초과로 계산된다")
        void calculatesTpSlOnShortEntry() {
            // leverage=10 → 10% → entry=50000, SHORT TP=45000(아래쪽), SL=55000(위쪽)
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.SHORT, 10);
            QueueStateDTO state = leafReadyState();
            state.setDirection(Side.SHORT);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            given(marketService.convertUsdtToQty(any(), any(), any())).willReturn("0.002");

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            assertThat(state.getTpPrice()).isEqualByComparingTo(new BigDecimal("45000.00"));
            assertThat(state.getSlPrice()).isEqualByComparingTo(new BigDecimal("55000.00"));
        }

        @Test
        @DisplayName("S:L 패턴 - 트리거 SHORT지만 leaf=LONG이므로 LONG 포지션으로 진입한다")
        void enterLongOnShortLeafLongPattern() {
            // S:L 패턴 = cond(SHORT) + leaf(LONG): 진입 방향은 leaf side(LONG)
            PatternQueue queue = makePatternWithCondAndLeafSides(Side.SHORT, Side.LONG, 10);
            QueueStateDTO state = leafReadyState();
            state.setDirection(Side.SHORT); // 트리거가 SHORT 방향이었음을 시뮬레이션
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            given(marketService.convertUsdtToQty(any(), any(), any())).willReturn("0.002");

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            // 진입 방향은 leaf side(LONG)로 갱신되어야 함
            assertThat(state.getDirection()).isEqualTo(Side.LONG);
            // TP/SL은 LONG 기준 — 익절가는 진입가보다 높고, 손절가는 낮아야 함
            assertThat(state.getTpPrice()).isEqualByComparingTo(new BigDecimal("55000.00"));
            assertThat(state.getSlPrice()).isEqualByComparingTo(new BigDecimal("45000.00"));
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
            // Bybit 주문 side 검증: LONG → "Buy"
            ArgumentCaptor<CreateOrderRequest> orderCaptor = ArgumentCaptor.forClass(CreateOrderRequest.class);
            then(tradeFacade).should().placeOrder(orderCaptor.capture(), any(), any(), any());
            assertThat(orderCaptor.getValue().getSide()).isEqualTo("Buy");
        }

        @Test
        @DisplayName("L:S 패턴 - 트리거 LONG지만 leaf=SHORT이므로 SHORT 포지션으로 진입한다")
        void enterShortOnLongLeafShortPattern() {
            // L:S 패턴 = cond(LONG) + leaf(SHORT): 진입 방향은 leaf side(SHORT)
            PatternQueue queue = makePatternWithCondAndLeafSides(Side.LONG, Side.SHORT, 10);
            QueueStateDTO state = leafReadyState();
            state.setDirection(Side.LONG); // 트리거가 LONG 방향이었음을 시뮬레이션
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            given(marketService.convertUsdtToQty(any(), any(), any())).willReturn("0.002");

            autoTradeService.processBlockMatching(queue, state, session, "50000.00");

            // 진입 방향은 leaf side(SHORT)로 갱신
            assertThat(state.getDirection()).isEqualTo(Side.SHORT);
            // TP/SL은 SHORT 기준 — 익절가는 진입가보다 낮고, 손절가는 높아야 함
            assertThat(state.getTpPrice()).isEqualByComparingTo(new BigDecimal("45000.00"));
            assertThat(state.getSlPrice()).isEqualByComparingTo(new BigDecimal("55000.00"));
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_HOLDING);
            // Bybit 주문 side 검증: SHORT → "Sell"
            ArgumentCaptor<CreateOrderRequest> orderCaptor = ArgumentCaptor.forClass(CreateOrderRequest.class);
            then(tradeFacade).should().placeOrder(orderCaptor.capture(), any(), any(), any());
            assertThat(orderCaptor.getValue().getSide()).isEqualTo("Sell");
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
        queue.setId(1L);
        queue.setUserId(1L);
        queue.setSymbol("BTCUSDT");
        queue.setActive(true);
        queue.setTriggerRate(new BigDecimal("1.0"));
        queue.setFull(false);
        ReflectionTestUtils.setField(queue, "createdAt", LocalDateTime.now());

        PatternBlock c1 = new PatternBlock(); c1.setId(101L); c1.setSide(Side.LONG); c1.setBlockOrder(1); c1.setLeaf(false);
        PatternBlock c2 = new PatternBlock(); c2.setId(102L); c2.setSide(Side.LONG); c2.setBlockOrder(2); c2.setLeaf(false);
        PatternBlock leaf = new PatternBlock(); leaf.setId(103L); leaf.setSide(Side.LONG); leaf.setBlockOrder(3); leaf.setLeaf(true);

        Pattern pattern = new Pattern();
        pattern.setId(10L);
        pattern.setPatternOrder(1);
        pattern.setAmount(new BigDecimal("100"));
        pattern.setLeverage(leverage);
        pattern.getBlocks().add(c1);
        pattern.getBlocks().add(c2);
        pattern.getBlocks().add(leaf);

        PatternStep step = new PatternStep();
        step.setId(10L);
        step.setStepLevel(1);
        step.setFull(false);
        step.getPatterns().add(pattern);
        step.setQueue(queue);
        queue.getSteps().add(step);
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
            state.setActiveStepId(10L);
            state.setCurrentBlockOrder(2);
            // blockBaseTime은 null 상태
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            autoTradeService.processBlockMatching(queue, state, session, "50100.00");

            // blockBaseTime/Price는 세팅되고, 블록 위치는 변하지 않음 (신호 판단은 봉 마감 시)
            assertThat(state.getBlockBaseTime()).isNotNull();
            assertThat(state.getBlockBasePrice()).isEqualTo("50100.00");
            assertThat(state.getCurrentBlockOrder()).isEqualTo(2);
            assertThat(state.getPhase()).isEqualTo(TradePhase.BLOCK_MATCHING);
            then(tradeFacade).should(never()).placeOrder(any(), any(), any(), any());
        }

        @Test
        @DisplayName("조건 블록에서 blockBaseTime이 이미 세팅되어 있으면 처리하지 않는다")
        void doesNothingWhenBlockBaseAlreadySet() {
            PatternQueue queue = makeThreeBlockLongQueue(10);
            QueueStateDTO state = QueueStateDTO.initial(1L);
            state.setPhase(TradePhase.BLOCK_MATCHING);
            state.setDirection(Side.LONG);
            state.setActivePatternId(10L);
            state.setActiveStepId(10L);
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
            then(tradeFacade).should(never()).placeOrder(any(), any(), any(), any());
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
            state.setActiveStepId(10L);
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
            then(tradeFacade).should(never()).placeOrder(any(), any(), any(), any());
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
            then(tradeFacade).should(times(1)).placeOrder(any(), any(), any(TradeHistory.class), any());
            then(tradeHistoryRepository).should(times(1)).save(any(TradeHistory.class));
            then(investmentHistoryRepository).should(times(1)).save(any(InvestmentHistory.class));

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
        @DisplayName("LONG SL 도달 시 청산(SHORT 신호) → 2단계 SHORT 패턴 이동 (currentBlockOrder=2)")
        void longSlReached_liquidateAndMoveToStep2Short() {
            PatternQueue queue = makeTwoStepQueue(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = holdingState(Side.LONG,
                    new BigDecimal("55000"), new BigDecimal("45000"));
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            // SL 도달
            autoTradeService.processPositionHolding(queue, state, session, "44500.00");

            // 청산 주문 + 거래/투자 이력 저장
            then(tradeFacade).should(times(1)).placeOrder(any(), any(), any(), any());
            then(investmentHistoryRepository).should(times(1)).save(any(InvestmentHistory.class));

            // 2단계 SHORT 이동, currentBlockOrder=2
            assertThat(state.getCurrentStepLevel()).isEqualTo(2);
            assertThat(state.getDirection()).isEqualTo(Side.SHORT);
            assertThat(state.getCurrentBlockOrder()).isEqualTo(2);
            assertThat(state.getPhase()).isEqualTo(TradePhase.BLOCK_MATCHING);
            assertThat(state.getTpPrice()).isNull();
            assertThat(state.getSlPrice()).isNull();
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

            then(tradeFacade).should(times(1)).placeOrder(any(), any(), any(), any());
            then(investmentHistoryRepository).should(times(1)).save(any(InvestmentHistory.class));
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

            then(tradeFacade).should(times(1)).placeOrder(any(), any(), any(), any());
            then(investmentHistoryRepository).should(times(1)).save(any(InvestmentHistory.class));
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

            then(tradeFacade).should(times(1)).placeOrder(any(), any(), any(), any());
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

            then(tradeFacade).should(never()).placeOrder(any(), any(), any(), any());
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
            then(tradeFacade).should(never()).placeOrder(any(), any(), any(), any());
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

            then(tradeFacade).should(never()).placeOrder(any(), any(), any(), any());
            assertThat(state.getCloseSkipCount()).isEqualTo(1);
            assertThat(queue.isActive()).isTrue();
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
            var response = autoTradeService.getStatusResponse(1L, "BTCUSDT", TradeMode.MAIN);

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
            given(patternQueueRepository.findByUserIdAndSymbolAndIsActiveAndTradeModeOrderByCreatedAtAsc(
                    userId, symbol, true, TradeMode.MAIN)).willReturn(List.of(queue));
            autoTradeService.syncSession(userId, symbol, TradeMode.MAIN);

            // 큐 상태에 기준가/시각 설정 (30초 전)
            var state = autoTradeService.getSession(userId, symbol, TradeMode.MAIN).getQueueStates().get(1L);
            state.setBasePrice("50000.00");
            state.setBaseTime(LocalDateTime.now().minusSeconds(30));

            // WebSocket 현재가 모킹
            FindTickerResponse.TickerInfo wsTicker = new FindTickerResponse.TickerInfo();
            wsTicker.setLastPrice("50500.00");
            given(marketService.getWsTicker(symbol)).willReturn(wsTicker);

            var response = autoTradeService.getStatusResponse(userId, symbol, TradeMode.MAIN);

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
            given(patternQueueRepository.findByUserIdAndSymbolAndIsActiveAndTradeModeOrderByCreatedAtAsc(
                    userId, symbol, true, TradeMode.MAIN)).willReturn(List.of(queue));
            autoTradeService.syncSession(userId, symbol, TradeMode.MAIN);

            // POSITION_HOLDING 상태로 설정 (활성 패턴 + 진입가 + TP/SL)
            var state = autoTradeService.getSession(userId, symbol, TradeMode.MAIN).getQueueStates().get(1L);
            state.setPhase(TradePhase.POSITION_HOLDING);
            state.setDirection(Side.LONG);
            state.setEntryPrice("50000.00");
            state.setTpPrice(new BigDecimal("55000.00"));
            state.setSlPrice(new BigDecimal("45000.00"));
            state.setActivePatternId(10L); // makeStep에서 pattern.id = queueId * 10 = 10

            // WebSocket 현재가 모킹
            FindTickerResponse.TickerInfo wsTicker = new FindTickerResponse.TickerInfo();
            wsTicker.setLastPrice("52500.00");
            given(marketService.getWsTicker(symbol)).willReturn(wsTicker);

            var response = autoTradeService.getStatusResponse(userId, symbol, TradeMode.MAIN);

            assertThat(response.isActive()).isTrue();
            assertThat(response.getPhase()).isEqualTo("POSITION_HOLDING");
            // 50000 → 52500 = +5.0%
            assertThat(response.getChangeRate()).isNotNull();
            assertThat(response.getChangeRate().doubleValue()).isCloseTo(5.0, org.assertj.core.api.Assertions.within(0.01));
            // 투입 금액 = 100 USDT (makeStep에서 설정)
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
            given(patternQueueRepository.findByUserIdAndSymbolAndIsActiveAndTradeModeOrderByCreatedAtAsc(
                    userId, symbol, true, TradeMode.MAIN)).willReturn(List.of(queue));
            autoTradeService.syncSession(userId, symbol, TradeMode.MAIN);

            var state = autoTradeService.getSession(userId, symbol, TradeMode.MAIN).getQueueStates().get(1L);
            state.setPhase(TradePhase.POSITION_HOLDING);
            state.setDirection(Side.LONG);
            state.setEntryPrice("50000.00");
            state.setActivePatternId(10L);

            FindTickerResponse.TickerInfo wsTicker = new FindTickerResponse.TickerInfo();
            wsTicker.setLastPrice("52500.00");
            given(marketService.getWsTicker(symbol)).willReturn(wsTicker);

            var response = autoTradeService.getStatusResponse(userId, symbol, TradeMode.MAIN);

            assertThat(response.getEntryPrice()).isEqualTo("50000.00");
        }

        @Test
        @DisplayName("WebSocket 가격이 없으면 변동률 null을 반환한다")
        void returnsNullChangeRateWhenNoWsPrice() {
            Long userId = 1L;
            String symbol = "BTCUSDT";
            PatternQueue queue = makePatternQueue(1L, userId, symbol, Side.LONG);
            given(patternQueueRepository.findByUserIdAndSymbolAndIsActiveAndTradeModeOrderByCreatedAtAsc(
                    userId, symbol, true, TradeMode.MAIN)).willReturn(List.of(queue));
            autoTradeService.syncSession(userId, symbol, TradeMode.MAIN);

            // WebSocket 가격 없음
            given(marketService.getWsTicker(symbol)).willReturn(null);

            var response = autoTradeService.getStatusResponse(userId, symbol, TradeMode.MAIN);

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
    @DisplayName("pushAutoTradeStatus: 세션의 현재 상태를 STOMP로 /topic/autotrade.status.{symbol}에 push한다")
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
        given(marketService.getWsTicker(symbol))
                .willReturn(makeTickerInfo(symbol, "50000.00"));

        // when - onPriceUpdate 호출 → pushAutoTradeStatus 내부 호출
        autoTradeService.onPriceUpdate(symbol, "50000.00");

        // then - STOMP로 상태가 push되어야 함
        then(messagingTemplate).should(atLeastOnce())
                .convertAndSend(
                        argThat(destination -> destination.equals("/topic/autotrade.status." + symbol)),
                        any(AutoTradeStatusResponse.class)
                );
    }

    // ─────────────────────────────────────────────
    // 헬퍼 메서드
    // ─────────────────────────────────────────────

    /**
     * 테스트용 세션 생성
     */
    private AutoTradeSessionDTO makeSession(Long userId, String symbol, PatternQueue queue) {
        AutoTradeSessionDTO session = new AutoTradeSessionDTO();
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
        queue.setId(id);
        queue.setUserId(userId);
        queue.setSymbol(symbol);
        queue.setActive(true);

        queue.setTriggerRate(new BigDecimal("1.0"));
        queue.setFull(false);
        ReflectionTestUtils.setField(queue, "createdAt", LocalDateTime.now());

        PatternStep step = makeStep(id, leafSide, leverage, 1);
        step.setQueue(queue);
        queue.getSteps().add(step);
        return queue;
    }

    /**
     * cond 블록과 leaf 블록의 side를 다르게 지정한 PatternQueue 생성
     * (예: cond=SHORT, leaf=LONG → "S:L" 패턴)
     */
    private PatternQueue makePatternWithCondAndLeafSides(Side condSide, Side leafSide, int leverage) {
        PatternQueue queue = new PatternQueue();
        queue.setId(1L);
        queue.setUserId(1L);
        queue.setSymbol("BTCUSDT");
        queue.setActive(true);
        queue.setTriggerRate(new BigDecimal("1.0"));
        queue.setFull(false);
        ReflectionTestUtils.setField(queue, "createdAt", LocalDateTime.now());

        PatternBlock cond = new PatternBlock();
        cond.setId(101L);
        cond.setSide(condSide);
        cond.setBlockOrder(1);
        cond.setLeaf(false);

        PatternBlock leaf = new PatternBlock();
        leaf.setId(102L);
        leaf.setSide(leafSide);
        leaf.setBlockOrder(2);
        leaf.setLeaf(true);

        Pattern pattern = new Pattern();
        pattern.setId(10L);
        pattern.setPatternOrder(1);
        pattern.setAmount(new BigDecimal("100"));
        pattern.setLeverage(leverage);
        pattern.getBlocks().add(cond);
        pattern.getBlocks().add(leaf);

        PatternStep step = new PatternStep();
        step.setId(10L);
        step.setStepLevel(1);
        step.setFull(false);
        step.getPatterns().add(pattern);
        step.setQueue(queue);
        queue.getSteps().add(step);
        return queue;
    }

    /**
     * LONG, SHORT 양쪽 패턴을 가진 PatternQueue 생성
     */
    private PatternQueue makePatternQueueWithBothPatterns(Long id, Long userId, String symbol) {
        PatternQueue queue = new PatternQueue();
        queue.setId(id);
        queue.setUserId(userId);
        queue.setSymbol(symbol);
        queue.setActive(true);

        queue.setTriggerRate(new BigDecimal("1.0"));
        queue.setFull(false);
        ReflectionTestUtils.setField(queue, "createdAt", LocalDateTime.now());

        PatternStep step = makeStepWithBothPatterns(1);
        step.setId(id * 10);
        step.setQueue(queue);
        queue.getSteps().add(step);
        return queue;
    }

    /**
     * 2단계 큐 생성 (1단계: LONG패턴, 2단계: LONG+SHORT 패턴)
     */
    private PatternQueue makeTwoStepQueue(Long id, Long userId, String symbol, int leverage) {
        PatternQueue queue = new PatternQueue();
        queue.setId(id);
        queue.setUserId(userId);
        queue.setSymbol(symbol);
        queue.setActive(true);

        queue.setTriggerRate(new BigDecimal("1.0"));
        queue.setFull(false);
        ReflectionTestUtils.setField(queue, "createdAt", LocalDateTime.now());

        // 1단계: LONG 패턴
        PatternStep step1 = makeStep(id, Side.LONG, leverage, 1);
        step1.setQueue(queue);
        queue.getSteps().add(step1);

        // 2단계: LONG + SHORT 패턴
        PatternStep step2 = makeStepWithBothPatterns(2);
        step2.setId(id * 10 + 1);
        step2.setQueue(queue);
        // 패턴에 레버리지 설정
        step2.getPatterns().forEach(p -> p.setLeverage(leverage));
        queue.getSteps().add(step2);

        return queue;
    }

    /**
     * 단일 방향 패턴을 가진 단계 생성
     */
    private PatternStep makeStep(Long queueId, Side side, int leverage, int stepLevel) {
        // 조건 블록
        PatternBlock condBlock = new PatternBlock();
        condBlock.setId(queueId * 100 + 1);
        condBlock.setSide(side);
        condBlock.setBlockOrder(1);
        condBlock.setLeaf(false);

        // 리프 블록
        PatternBlock leafBlock = new PatternBlock();
        leafBlock.setId(queueId * 100 + 2);
        leafBlock.setSide(side);
        leafBlock.setBlockOrder(2);
        leafBlock.setLeaf(true);

        // 패턴
        Pattern pattern = new Pattern();
        pattern.setId(queueId * 10);
        pattern.setPatternOrder(1);
        pattern.setAmount(new BigDecimal("100"));
        pattern.setLeverage(leverage);
        pattern.getBlocks().add(condBlock);
        pattern.getBlocks().add(leafBlock);

        // 단계
        PatternStep step = new PatternStep();
        step.setId(queueId * 10);
        step.setStepLevel(stepLevel);
        step.setFull(false);
        step.getPatterns().add(pattern);

        return step;
    }

    /**
     * LONG + SHORT 양쪽 패턴을 가진 단계 생성
     */
    private PatternStep makeStepWithBothPatterns(int stepLevel) {
        // LONG 패턴
        PatternBlock longCond = new PatternBlock();
        longCond.setId(1001L);
        longCond.setSide(Side.LONG);
        longCond.setBlockOrder(1);
        longCond.setLeaf(false);

        PatternBlock longLeaf = new PatternBlock();
        longLeaf.setId(1002L);
        longLeaf.setSide(Side.LONG);
        longLeaf.setBlockOrder(2);
        longLeaf.setLeaf(true);

        Pattern longPattern = new Pattern();
        longPattern.setId(100L);
        longPattern.setPatternOrder(1);
        longPattern.setAmount(new BigDecimal("100"));
        longPattern.setLeverage(1);
        longPattern.getBlocks().add(longCond);
        longPattern.getBlocks().add(longLeaf);

        // SHORT 패턴
        PatternBlock shortCond = new PatternBlock();
        shortCond.setId(2001L);
        shortCond.setSide(Side.SHORT);
        shortCond.setBlockOrder(1);
        shortCond.setLeaf(false);

        PatternBlock shortLeaf = new PatternBlock();
        shortLeaf.setId(2002L);
        shortLeaf.setSide(Side.SHORT);
        shortLeaf.setBlockOrder(2);
        shortLeaf.setLeaf(true);

        Pattern shortPattern = new Pattern();
        shortPattern.setId(200L);
        shortPattern.setPatternOrder(2);
        shortPattern.setAmount(new BigDecimal("100"));
        shortPattern.setLeverage(1);
        shortPattern.getBlocks().add(shortCond);
        shortPattern.getBlocks().add(shortLeaf);

        PatternStep step = new PatternStep();
        step.setStepLevel(stepLevel);
        step.setFull(true);
        step.getPatterns().add(longPattern);
        step.getPatterns().add(shortPattern);

        return step;
    }

    /**
     * LONG+SHORT 양쪽 패턴에 레버리지를 지정한 PatternQueue 생성
     * - Long 패턴 id=100, Short 패턴 id=200
     */
    private PatternQueue makeBothPatternQueueWithLeverage(Long id, Long userId, String symbol, int leverage) {
        PatternQueue queue = makePatternQueueWithBothPatterns(id, userId, symbol);
        // makeStepWithBothPatterns에서 leverage=1로 고정하므로 여기서 override
        queue.getSteps().forEach(step ->
                step.getPatterns().forEach(p -> p.setLeverage(leverage)));
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

    /**
     * TickerInfo 헬퍼 메서드 (STOMP 테스트용)
     */
    private FindTickerResponse.TickerInfo makeTickerInfo(String symbol, String price) {
        FindTickerResponse.TickerInfo ticker = new FindTickerResponse.TickerInfo();
        ticker.setSymbol(symbol);
        ticker.setLastPrice(price);
        ticker.setVolume24h("10000.0");
        ticker.setPrice24hPcnt("0.01");
        ticker.setHighPrice24h("51000.0");
        ticker.setLowPrice24h("49000.0");
        ticker.setTurnover24h("1000000.0");
        return ticker;
    }
}
