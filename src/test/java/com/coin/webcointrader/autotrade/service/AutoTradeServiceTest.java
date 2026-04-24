package com.coin.webcointrader.autotrade.service;

import com.coin.webcointrader.autotrade.dto.AutoTradeSessionDTO;
import com.coin.webcointrader.autotrade.dto.AutoTradeStatusResponse;
import com.coin.webcointrader.autotrade.dto.QueueStateDTO;
import com.coin.webcointrader.autotrade.dto.TradePhase;
import com.coin.webcointrader.autotrade.repository.InvestmentHistoryRepository;
import com.coin.webcointrader.autotrade.repository.PatternQueueRepository;
import com.coin.webcointrader.autotrade.repository.TradeHistoryRepository;
import com.coin.webcointrader.common.client.market.BybitWebSocketClient;
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
        @DisplayName("상승률 충족 시 LONG으로 전환한다")
        void transitionsToLongOnRiseAboveTrigger() {
            PatternQueue queue = makePatternQueue(1L, 1L, "BTCUSDT", Side.LONG);
            QueueStateDTO state = QueueStateDTO.initial(1L);
            state.setBasePrice("50000.00");
            state.setBaseTime(LocalDateTime.now());
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            // 50000 → 50600 = +1.2% (triggerRate=1.0% 초과)
            autoTradeService.processTriggerWait(queue, state, session, "50600.00");

            assertThat(state.getDirection()).isEqualTo(Side.LONG);
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_OPEN);
        }

        @Test
        @DisplayName("하락률 충족 시 SHORT으로 전환한다")
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
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_OPEN);
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
    // 신호 판별 (determineSignal)
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("determineSignal")
    class DetermineSignalTest {

        @Test
        @DisplayName("레버리지 기반 임계값으로 LONG 신호를 판별한다")
        void detectsLongSignalWithLeverage() {
            QueueStateDTO state = QueueStateDTO.initial(1L);
            state.setDirection(Side.LONG);
            state.setEntryPrice("50000.00");

            // leverage=10 → threshold=10%
            Pattern pattern = makePatternWithLeverage(10);

            // 50000 → 55500 = +11% → LONG
            Side signal = autoTradeService.determineSignal(state, pattern, "55500.00");
            assertThat(signal).isEqualTo(Side.LONG);
        }

        @Test
        @DisplayName("레버리지 기반 임계값으로 SHORT 신호를 판별한다")
        void detectsShortSignalWithLeverage() {
            QueueStateDTO state = QueueStateDTO.initial(1L);
            state.setDirection(Side.LONG);
            state.setEntryPrice("50000.00");

            Pattern pattern = makePatternWithLeverage(10);

            // 50000 → 44500 = -11% → SHORT
            Side signal = autoTradeService.determineSignal(state, pattern, "44500.00");
            assertThat(signal).isEqualTo(Side.SHORT);
        }

        @Test
        @DisplayName("임계값 미달 시 null을 반환한다")
        void returnsNullWhenBelowThreshold() {
            QueueStateDTO state = QueueStateDTO.initial(1L);
            state.setDirection(Side.LONG);
            state.setEntryPrice("50000.00");

            Pattern pattern = makePatternWithLeverage(10);

            // 50000 → 52000 = +4% (threshold=10% 미달)
            Side signal = autoTradeService.determineSignal(state, pattern, "52000.00");
            assertThat(signal).isNull();
        }

        @Test
        @DisplayName("손절 5%, 익절 10% LONG일 때 +5.2%는 신호 없음 (익절 임계값 미달)")
        void noSignalWhenBelowProfitThreshold() {
            QueueStateDTO state = QueueStateDTO.initial(1L);
            state.setDirection(Side.LONG);
            state.setEntryPrice("50000.00");

            // stopLossRate=5%, takeProfitRate=10%
            Pattern pattern = makePatternWithLeverage(10);
            pattern.setStopLossRate(new BigDecimal("5.00"));
            pattern.setTakeProfitRate(new BigDecimal("10.00"));

            // 50000 → 52600 = +5.2% (익절 10% 미달, 손절 -5% 미달)
            Side signal = autoTradeService.determineSignal(state, pattern, "52600.00");
            assertThat(signal).isNull();
        }

        @Test
        @DisplayName("손절 5%, 익절 10% LONG일 때 +10% 도달 시 수익 신호")
        void profitSignalAtTakeProfitRate() {
            QueueStateDTO state = QueueStateDTO.initial(1L);
            state.setDirection(Side.LONG);
            state.setEntryPrice("50000.00");

            Pattern pattern = makePatternWithLeverage(10);
            pattern.setStopLossRate(new BigDecimal("5.00"));
            pattern.setTakeProfitRate(new BigDecimal("10.00"));

            // 50000 → 55000 = +10% → LONG(수익)
            Side signal = autoTradeService.determineSignal(state, pattern, "55000.00");
            assertThat(signal).isEqualTo(Side.LONG);
        }

        @Test
        @DisplayName("손절 5%, 익절 10% LONG일 때 -5% 도달 시 손실 신호")
        void lossSignalAtStopLossRate() {
            QueueStateDTO state = QueueStateDTO.initial(1L);
            state.setDirection(Side.LONG);
            state.setEntryPrice("50000.00");

            Pattern pattern = makePatternWithLeverage(10);
            pattern.setStopLossRate(new BigDecimal("5.00"));
            pattern.setTakeProfitRate(new BigDecimal("10.00"));

            // 50000 → 47500 = -5% → SHORT(손실)
            Side signal = autoTradeService.determineSignal(state, pattern, "47500.00");
            assertThat(signal).isEqualTo(Side.SHORT);
        }

        @Test
        @DisplayName("손절만 설정 시 수익 임계값은 레버리지 기반으로 적용된다")
        void usesLeverageForProfitWhenOnlyStopLossSet() {
            QueueStateDTO state = QueueStateDTO.initial(1L);
            state.setDirection(Side.LONG);
            state.setEntryPrice("50000.00");

            // stopLossRate=5%, takeProfitRate=null → 수익 임계값=100/10=10%
            Pattern pattern = makePatternWithLeverage(10);
            pattern.setStopLossRate(new BigDecimal("5.00"));

            // 50000 → 55500 = +11% → LONG(수익, 레버리지 기반 10% 초과)
            Side signal = autoTradeService.determineSignal(state, pattern, "55500.00");
            assertThat(signal).isEqualTo(Side.LONG);
        }
    }

    // ─────────────────────────────────────────────
    // 포지션 진입 (processPositionOpen)
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("processPositionOpen")
    class ProcessPositionOpenTest {

        @Test
        @DisplayName("진입 시 notional(amount × leverage)로 convertUsdtToQty를 호출한다")
        void callsConvertUsdtToQtyWithNotional() {
            // amount=100, leverage=10 → notional=1000
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = QueueStateDTO.initial(1L);
            state.setPhase(TradePhase.POSITION_OPEN);
            state.setDirection(Side.LONG);
            state.setActiveStepId(10L);
            state.setActivePatternId(10L);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            ArgumentCaptor<BigDecimal> notionalCaptor = ArgumentCaptor.forClass(BigDecimal.class);
            given(marketService.convertUsdtToQty(eq("BTCUSDT"), notionalCaptor.capture(), any()))
                    .willReturn("0.002");

            autoTradeService.processPositionOpen(queue, state, session, "50000.00");

            // amount(100) × leverage(10) = 1000 USDT 로 호출되어야 함
            assertThat(notionalCaptor.getValue()).isEqualByComparingTo(new BigDecimal("1000"));
        }

        @Test
        @DisplayName("진입 성공 시 entryQty와 entryNotional을 state에 저장한다")
        void savesEntryQtyAndNotionalOnSuccess() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = QueueStateDTO.initial(1L);
            state.setPhase(TradePhase.POSITION_OPEN);
            state.setDirection(Side.LONG);
            state.setActiveStepId(10L);
            state.setActivePatternId(10L);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            // qty=0.002, price=50000 → actualAmount = 0.002 × 50000 = 100 USDT
            given(marketService.convertUsdtToQty(any(), any(), any())).willReturn("0.002");

            autoTradeService.processPositionOpen(queue, state, session, "50000.00");

            assertThat(state.getEntryQty()).isEqualTo("0.002");
            assertThat(state.getEntryNotional()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(state.getPhase()).isEqualTo(TradePhase.BLOCK_MATCHING);
        }

        @Test
        @DisplayName("진입 성공 시 closeSkipCount를 0으로 초기화한다")
        void resetsCloseSkipCountOnSuccess() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = QueueStateDTO.initial(1L);
            state.setPhase(TradePhase.POSITION_OPEN);
            state.setDirection(Side.LONG);
            state.setActiveStepId(10L);
            state.setActivePatternId(10L);
            state.setCloseSkipCount(3); // 이전에 스킵이 있었던 상태 가정
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            given(marketService.convertUsdtToQty(any(), any(), any())).willReturn("0.002");

            autoTradeService.processPositionOpen(queue, state, session, "50000.00");

            assertThat(state.getCloseSkipCount()).isEqualTo(0);
        }
    }

    // ─────────────────────────────────────────────
    // 블록 매칭 (processBlockMatching)
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("processBlockMatching")
    class BlockMatchingTest {

        @Test
        @DisplayName("임계값 미달 시 아무 작업도 하지 않는다")
        void doesNothingWhenBelowThreshold() {
            PatternQueue queue = makePatternQueue(1L, 1L, "BTCUSDT", Side.LONG);
            QueueStateDTO state = QueueStateDTO.initial(1L);
            state.setPhase(TradePhase.BLOCK_MATCHING);
            state.setDirection(Side.LONG);
            state.setEntryPrice("50000.00");
            state.setActivePatternId(10L); // makePatternQueue에서 pattern id = 1*10 = 10
            state.setCurrentBlockOrder(2);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            // 50000 → 50100 = +0.2% (leverage=1 → threshold=100%, 미달)
            autoTradeService.processBlockMatching(queue, state, session, "50100.00");

            // 상태 변화 없음
            assertThat(state.getPhase()).isEqualTo(TradePhase.BLOCK_MATCHING);
            assertThat(state.getCurrentBlockOrder()).isEqualTo(2);
        }

        @Test
        @DisplayName("리프 블록 일치 시 매도 성공하고 같은 단계를 반복한다")
        void sellSuccessAndRepeatStep() {
            // leverage=1 → threshold=100%로는 테스트 불가, leverage=10 사용
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = QueueStateDTO.initial(1L);
            state.setPhase(TradePhase.BLOCK_MATCHING);
            state.setDirection(Side.LONG);
            state.setEntryPrice("50000.00");
            state.setActivePatternId(10L);
            state.setActiveStepId(10L);
            // 리프 블록은 blockOrder=2
            state.setCurrentBlockOrder(2);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            // 진입 시 저장되는 값 직접 설정 (processPositionOpen에서 저장, qty=0.001, price=50000 → notional=50)
            state.setEntryQty("0.001");
            state.setEntryNotional(new BigDecimal("50"));

            // 50000 → 55500 = +11% (threshold=10%) → LONG → 리프 LONG 일치
            autoTradeService.processBlockMatching(queue, state, session, "55500.00");

            // 매도 성공 → 같은 단계 반복 (POSITION_OPEN)
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_OPEN);
            assertThat(state.getCurrentBlockOrder()).isEqualTo(1);
            // 매도 주문 실행 확인 (reduceOnly 포함)
            then(tradeFacade).should(times(1)).placeOrder(any(), any(), any(TradeHistory.class), any());
            // 거래 이력 저장 확인
            then(tradeHistoryRepository).should(times(1)).save(any(TradeHistory.class));
            // 투자 히스토리 저장 확인 (포지션 사이클 완료 기록)
            then(investmentHistoryRepository).should(times(1)).save(any(InvestmentHistory.class));
        }

        @Test
        @DisplayName("반대 신호 시 기존 포지션을 청산하고 다음 단계로 이동한다")
        void movesToNextStepOnLiquidation() {
            // 2단계 큐 생성
            PatternQueue queue = makeTwoStepQueue(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = QueueStateDTO.initial(1L);
            state.setPhase(TradePhase.BLOCK_MATCHING);
            state.setDirection(Side.LONG);
            state.setEntryPrice("50000.00");
            state.setActivePatternId(10L);
            state.setActiveStepId(10L);
            state.setCurrentBlockOrder(2); // 리프 블록 위치
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            // 진입 시 저장되는 값 직접 설정
            state.setEntryQty("0.001");
            state.setEntryNotional(new BigDecimal("50"));

            // 50000 → 44500 = -11% (threshold=10%) → SHORT → 리프 LONG과 불일치 → 청산
            autoTradeService.processBlockMatching(queue, state, session, "44500.00");

            // 청산 주문 실행 검증
            then(tradeFacade).should(times(1)).placeOrder(any(), eq(1L), any(), any());
            then(tradeHistoryRepository).should(times(1)).save(any());
            // 투자 히스토리 저장 확인 (포지션 사이클 완료 기록)
            then(investmentHistoryRepository).should(times(1)).save(any(InvestmentHistory.class));

            // 다음 단계(2)로 이동, SHORT 방향
            assertThat(state.getCurrentStepLevel()).isEqualTo(2);
            assertThat(state.getDirection()).isEqualTo(Side.SHORT);
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_OPEN);
        }

        @Test
        @DisplayName("Long 매도 성공 후 1단계 Long 패턴으로 재진입한다 (SRS 11, 12)")
        void sellSuccess_long_resetsToStep1WithLongPattern() {
            // 1단계에 LONG(id=100) + SHORT(id=200) 양쪽 패턴, leverage=10
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = QueueStateDTO.initial(1L);
            state.setPhase(TradePhase.BLOCK_MATCHING);
            state.setDirection(Side.LONG);       // Long 포지션
            state.setEntryPrice("50000.00");
            state.setActivePatternId(100L);      // Long 패턴 (index 0)
            state.setActiveStepId(10L);
            state.setCurrentBlockOrder(2);       // 리프 블록
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            state.setEntryQty("0.001");
            state.setEntryNotional(new BigDecimal("50"));

            // 50000 → 55500 = +11% (threshold=10%) → LONG 신호 → 리프 LONG 일치 → 매도 성공
            autoTradeService.processBlockMatching(queue, state, session, "55500.00");

            // 매도 성공 → 1단계 Long 패턴(id=100)으로 재진입
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_OPEN);
            assertThat(state.getCurrentStepLevel()).isEqualTo(1);
            assertThat(state.getDirection()).isEqualTo(Side.LONG);
            assertThat(state.getActivePatternId()).isEqualTo(100L);
        }

        @Test
        @DisplayName("Short 매도 성공 후 1단계 Short 패턴으로 재진입한다 (SRS 11, 12)")
        void sellSuccess_short_resetsToStep1WithShortPattern() {
            // 1단계에 LONG(id=100) + SHORT(id=200) 양쪽 패턴, leverage=10
            PatternQueue queue = makeBothPatternQueueWithLeverage(1L, 1L, "BTCUSDT", 10);
            QueueStateDTO state = QueueStateDTO.initial(1L);
            state.setPhase(TradePhase.BLOCK_MATCHING);
            state.setDirection(Side.SHORT);      // Short 포지션
            state.setEntryPrice("50000.00");
            state.setActivePatternId(200L);      // Short 패턴 (index 1)
            state.setActiveStepId(10L);
            state.setCurrentBlockOrder(2);       // 리프 블록
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            state.setEntryQty("0.001");
            state.setEntryNotional(new BigDecimal("50"));

            // 50000 → 44500 = -11% (threshold=10%) → SHORT 신호 → 리프 SHORT 일치 → 매도 성공
            autoTradeService.processBlockMatching(queue, state, session, "44500.00");

            // 매도 성공 → 1단계 Short 패턴(id=200)으로 재진입
            assertThat(state.getPhase()).isEqualTo(TradePhase.POSITION_OPEN);
            assertThat(state.getCurrentStepLevel()).isEqualTo(1);
            assertThat(state.getDirection()).isEqualTo(Side.SHORT);
            assertThat(state.getActivePatternId()).isEqualTo(200L);
        }

        @Test
        @DisplayName("마지막 단계 청산 시 포지션을 닫고 큐를 비활성화한다")
        void deactivatesQueueOnLastStepLiquidation() {
            // 1단계만 있는 큐
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = QueueStateDTO.initial(1L);
            state.setPhase(TradePhase.BLOCK_MATCHING);
            state.setDirection(Side.LONG);
            state.setEntryPrice("50000.00");
            state.setActivePatternId(10L);
            state.setActiveStepId(10L);
            state.setCurrentBlockOrder(2);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            // 진입 시 저장되는 값 직접 설정
            state.setEntryQty("0.001");
            state.setEntryNotional(new BigDecimal("50"));

            // 청산 → 포지션 닫기 → 다음 단계 없음 → 큐 비활성화
            autoTradeService.processBlockMatching(queue, state, session, "44500.00");

            // 청산 주문 실행 검증
            then(tradeFacade).should(times(1)).placeOrder(any(), eq(1L), any(), any());
            // 투자 히스토리 저장 확인
            then(investmentHistoryRepository).should(times(1)).save(any(InvestmentHistory.class));

            assertThat(queue.isActive()).isFalse();
            then(patternQueueRepository).should(times(1)).save(queue);
        }

        @Test
        @DisplayName("매도 시 entryQty가 null이면 closeSkipCount를 증가시키고 매도를 스킵한다")
        void sellSkips_whenEntryQtyIsNull() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = QueueStateDTO.initial(1L);
            state.setPhase(TradePhase.BLOCK_MATCHING);
            state.setDirection(Side.LONG);
            state.setEntryPrice("50000.00");
            state.setActivePatternId(10L);
            state.setActiveStepId(10L);
            state.setCurrentBlockOrder(2); // 리프 블록
            state.setEntryQty(null);       // 진입 수량 없음 (비정상 상태 시뮬레이션)
            state.setEntryNotional(new BigDecimal("50"));
            state.setCloseSkipCount(0);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            // 55500: +11% → LONG 신호 → 리프 LONG 일치 → handleSellSuccess 진입
            autoTradeService.processBlockMatching(queue, state, session, "55500.00");

            // 주문 미실행, 스킵 카운터만 증가, 큐는 아직 활성
            then(tradeFacade).should(never()).placeOrder(any(), any(), any(), any());
            assertThat(state.getCloseSkipCount()).isEqualTo(1);
            assertThat(queue.isActive()).isTrue();
        }

        @Test
        @DisplayName("매도 시 entryQty null 스킵이 5회 도달하면 큐를 비활성화한다")
        void sellDeactivatesQueue_whenSkipCountReaches5() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = QueueStateDTO.initial(1L);
            state.setPhase(TradePhase.BLOCK_MATCHING);
            state.setDirection(Side.LONG);
            state.setEntryPrice("50000.00");
            state.setActivePatternId(10L);
            state.setActiveStepId(10L);
            state.setCurrentBlockOrder(2); // 리프 블록
            state.setEntryQty(null);       // 진입 수량 없음
            state.setEntryNotional(new BigDecimal("50"));
            state.setCloseSkipCount(4);    // 이미 4회 스킵 (다음 호출에서 5회 도달)
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            autoTradeService.processBlockMatching(queue, state, session, "55500.00");

            // 5회 도달 → 큐 비활성화, 주문 미실행
            assertThat(queue.isActive()).isFalse();
            then(patternQueueRepository).should(times(1)).save(queue);
            then(tradeFacade).should(never()).placeOrder(any(), any(), any(), any());
        }

        @Test
        @DisplayName("청산 시 entryQty가 null이면 closeSkipCount를 증가시키고 청산을 스킵한다")
        void liquidationSkips_whenEntryQtyIsNull() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = QueueStateDTO.initial(1L);
            state.setPhase(TradePhase.BLOCK_MATCHING);
            state.setDirection(Side.LONG);
            state.setEntryPrice("50000.00");
            state.setActivePatternId(10L);
            state.setActiveStepId(10L);
            state.setCurrentBlockOrder(2); // 리프 블록
            state.setEntryQty(null);       // 진입 수량 없음 (비정상 상태 시뮬레이션)
            state.setEntryNotional(new BigDecimal("50"));
            state.setCloseSkipCount(0);
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            // 44500: -11% → SHORT 신호 → 리프 LONG 불일치 → handleLiquidation 진입
            autoTradeService.processBlockMatching(queue, state, session, "44500.00");

            // 주문 미실행, 스킵 카운터만 증가, 큐는 아직 활성
            then(tradeFacade).should(never()).placeOrder(any(), any(), any(), any());
            assertThat(state.getCloseSkipCount()).isEqualTo(1);
            assertThat(queue.isActive()).isTrue();
        }

        @Test
        @DisplayName("청산 시 entryQty null 스킵이 5회 도달하면 큐를 비활성화한다")
        void liquidationDeactivatesQueue_whenSkipCountReaches5() {
            PatternQueue queue = makePatternQueueWithLeverage(1L, 1L, "BTCUSDT", Side.LONG, 10);
            QueueStateDTO state = QueueStateDTO.initial(1L);
            state.setPhase(TradePhase.BLOCK_MATCHING);
            state.setDirection(Side.LONG);
            state.setEntryPrice("50000.00");
            state.setActivePatternId(10L);
            state.setActiveStepId(10L);
            state.setCurrentBlockOrder(2); // 리프 블록
            state.setEntryQty(null);       // 진입 수량 없음
            state.setEntryNotional(new BigDecimal("50"));
            state.setCloseSkipCount(4);    // 이미 4회 스킵 (다음 호출에서 5회 도달)
            AutoTradeSessionDTO session = makeSession(1L, "BTCUSDT", queue);

            autoTradeService.processBlockMatching(queue, state, session, "44500.00");

            // 5회 도달 → 큐 비활성화, 주문 미실행
            assertThat(queue.isActive()).isFalse();
            then(patternQueueRepository).should(times(1)).save(queue);
            then(tradeFacade).should(never()).placeOrder(any(), any(), any(), any());
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
        @DisplayName("BLOCK_MATCHING 시 진입가 대비 변동률을 반환한다")
        void returnsChangeRateOnBlockMatching() {
            Long userId = 1L;
            String symbol = "BTCUSDT";
            PatternQueue queue = makePatternQueue(1L, userId, symbol, Side.LONG);
            given(patternQueueRepository.findByUserIdAndSymbolAndIsActiveAndTradeModeOrderByCreatedAtAsc(
                    userId, symbol, true, TradeMode.MAIN)).willReturn(List.of(queue));
            autoTradeService.syncSession(userId, symbol, TradeMode.MAIN);

            // BLOCK_MATCHING 상태로 설정 (활성 패턴 ID 포함)
            var state = autoTradeService.getSession(userId, symbol, TradeMode.MAIN).getQueueStates().get(1L);
            state.setPhase(TradePhase.BLOCK_MATCHING);
            state.setDirection(Side.LONG);
            state.setEntryPrice("50000.00");
            state.setActivePatternId(10L); // makeStep에서 pattern.id = queueId * 10 = 10

            // WebSocket 현재가 모킹
            FindTickerResponse.TickerInfo wsTicker = new FindTickerResponse.TickerInfo();
            wsTicker.setLastPrice("52500.00");
            given(marketService.getWsTicker(symbol)).willReturn(wsTicker);

            var response = autoTradeService.getStatusResponse(userId, symbol, TradeMode.MAIN);

            assertThat(response.isActive()).isTrue();
            assertThat(response.getPhase()).isEqualTo("BLOCK_MATCHING");
            assertThat(response.getElapsedSeconds()).isEqualTo(0); // BLOCK_MATCHING은 경과시간 없음
            // 50000 → 52500 = +5.0%
            assertThat(response.getChangeRate()).isNotNull();
            assertThat(response.getChangeRate().doubleValue()).isCloseTo(5.0, org.assertj.core.api.Assertions.within(0.01));
            // 투입 금액 = 100 USDT (makeStep에서 설정)
            assertThat(response.getAmount()).isNotNull();
            assertThat(response.getAmount()).isEqualByComparingTo(new BigDecimal("100"));
        }

        @Test
        @DisplayName("BLOCK_MATCHING 시 진입가(entryPrice)를 반환한다 (SRS 27)")
        void returnsEntryPriceOnBlockMatching() {
            Long userId = 1L;
            String symbol = "BTCUSDT";
            PatternQueue queue = makePatternQueue(1L, userId, symbol, Side.LONG);
            given(patternQueueRepository.findByUserIdAndSymbolAndIsActiveAndTradeModeOrderByCreatedAtAsc(
                    userId, symbol, true, TradeMode.MAIN)).willReturn(List.of(queue));
            autoTradeService.syncSession(userId, symbol, TradeMode.MAIN);

            // BLOCK_MATCHING 상태 + 진입가 설정
            var state = autoTradeService.getSession(userId, symbol, TradeMode.MAIN).getQueueStates().get(1L);
            state.setPhase(TradePhase.BLOCK_MATCHING);
            state.setDirection(Side.LONG);
            state.setEntryPrice("50000.00");
            state.setActivePatternId(10L);

            FindTickerResponse.TickerInfo wsTicker = new FindTickerResponse.TickerInfo();
            wsTicker.setLastPrice("52500.00");
            given(marketService.getWsTicker(symbol)).willReturn(wsTicker);

            var response = autoTradeService.getStatusResponse(userId, symbol, TradeMode.MAIN);

            // 진입가가 응답에 포함되어야 함
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
