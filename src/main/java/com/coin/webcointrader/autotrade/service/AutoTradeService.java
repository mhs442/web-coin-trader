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
import com.coin.webcointrader.common.dto.request.SetLeverageRequest;
import com.coin.webcointrader.common.dto.response.FindTickerResponse;
import com.coin.webcointrader.common.entity.*;
import com.coin.webcointrader.common.enums.Category;
import com.coin.webcointrader.common.enums.LogMessage;
import com.coin.webcointrader.common.enums.TradeMode;
import com.coin.webcointrader.common.enums.OrderResult;
import com.coin.webcointrader.common.enums.TradeOrderType;
import com.coin.webcointrader.market.service.MarketService;
import com.coin.webcointrader.common.entity.SimTradeHistory;
import com.coin.webcointrader.common.entity.SimInvestmentHistory;
import com.coin.webcointrader.sim.repository.SimTradeHistoryRepository;
import com.coin.webcointrader.sim.repository.SimInvestmentHistoryRepository;
import com.coin.webcointrader.trade.service.TradeFacade;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 자동매매 엔진.
 * WebSocket 실시간 가격 수신으로 이벤트 드리븐 매매를 실행하며,
 * 1초 주기 스케줄러는 WebSocket 장애 대비 안전망(fallback)으로 유지한다.
 *
 * 알고리즘 페이즈:
 * 1. TRIGGER_WAIT   - 트리거 조건 대기 (기준가 대비 ±triggerRate% 변동 시 방향 결정)
 * 2. BLOCK_MATCHING - 블록 순차 관찰 (60초 고정 대기 + 기준가 갱신 방식)
 *    - 조건 블록: 포지션 없이 관찰만 / leaf 직전 블록 일치 시 포지션 진입
 *    - leaf 블록: 일치 시 매도 성공 / 반대 시 청산
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AutoTradeService {
    private final PatternQueueRepository patternQueueRepository;
    private final TradeHistoryRepository tradeHistoryRepository;
    private final InvestmentHistoryRepository investmentHistoryRepository;
    private final SimTradeHistoryRepository simTradeHistoryRepository;
    private final SimInvestmentHistoryRepository simInvestmentHistoryRepository;
    private final TradeFacade tradeFacade;
    private final MarketService marketService;
    private final BybitWebSocketClient bybitWebSocketClient;
    private final SimpMessagingTemplate messagingTemplate; // STOMP 메시지 전송 템플릿 (서버 → 브라우저 push용)

    // 활성 세션 관리 (키: "userId:symbol")
    private final ConcurrentHashMap<String, AutoTradeSessionDTO> activeSessions = new ConcurrentHashMap<>();

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    // Bybit Linear(USDT 선물) 시장가 주문 taker 수수료율
    private static final BigDecimal TAKER_FEE_RATE = new BigDecimal("0.00055");
    // 블록 매칭 고정 대기 시간 (초)
    private static final long BLOCK_WAIT_SECONDS = 60L;

    /**
     * 애플리케이션 시작 시 WebSocket 가격/봉마감 리스너를 등록한다.
     * - 가격 push: leaf 즉시 진입, POSITION_HOLDING TP/SL 모니터링
     * - 봉 마감 push: 조건 블록 신호 판단 (1분봉 close vs open)
     */
    @PostConstruct
    public void init() {
        marketService.addPriceListener(this::onPriceUpdate);
        marketService.addKlineConfirmedListener(this::onKlineConfirmed);
        log.info(LogMessage.AUTO_TRADE_LISTENER_REGISTERED.getMessage());
    }

    /**
     * WebSocket에서 가격 변동이 수신되면 호출된다.
     * 해당 심볼의 활성 세션만 필터링하여 즉시 처리한다.
     *
     * @param symbol   변동이 발생한 심볼 (예: "BTCUSDT")
     * @param newPrice 새로운 가격
     */
    public void onPriceUpdate(String symbol, String newPrice) {
        if (activeSessions.isEmpty()) {
            return;
        }

        // 해당 심볼의 활성 세션만 필터링하여 처리
        for (AutoTradeSessionDTO session : activeSessions.values()) {
            if (session.getSymbol().equals(symbol)) {
                try {
                    processSessionWithPrice(session, newPrice);
                } catch (Exception e) {
                    log.error(LogMessage.AUTO_TRADE_WS_ERROR.getMessage(), symbol, e.getMessage());
                    session.addLog(now() + " [오류] " + e.getMessage());
                }
                // STOMP: 가격 변동마다 해당 세션의 자동매매 상태를 브라우저에 push
                pushAutoTradeStatus(session);
            }
        }
    }

    /**
     * Bybit가 1분봉 마감(confirm=true)을 알려준 시점에 호출된다.
     * 해당 심볼의 활성 세션 중 BLOCK_MATCHING 상태이고 조건 블록을 관찰 중인 큐를 찾아
     * 봉의 close vs open 비교로 신호 방향을 판정한다.
     *
     * <p>큐의 blockBaseTime이 이 봉의 [start, end] 구간에 속할 때만 처리하여
     * 진입 직후 다른 봉의 마감 알림에 반응하지 않도록 한다.</p>
     *
     * @param symbol 심볼
     * @param kline  마감된 1분봉 데이터
     */
    public void onKlineConfirmed(String symbol, WebSocketKlineDTO.KlineData kline) {
        if (activeSessions.isEmpty() || kline == null) return;
        if (kline.getStart() == null || kline.getEnd() == null
                || kline.getOpen() == null || kline.getClose() == null) return;

        // 봉의 신호 = 양봉/음봉/도지
        BigDecimal open = new BigDecimal(kline.getOpen());
        BigDecimal close = new BigDecimal(kline.getClose());
        Side signal;
        int cmp = close.compareTo(open);
        if (cmp > 0) signal = Side.LONG;
        else if (cmp < 0) signal = Side.SHORT;
        else signal = null; // 도지 — 다음 봉 재대기

        for (AutoTradeSessionDTO session : activeSessions.values()) {
            if (!session.getSymbol().equals(symbol)) continue;
            try {
                applyKlineSignal(session, kline, signal);
                pushAutoTradeStatus(session);
            } catch (Exception e) {
                log.error(LogMessage.AUTO_TRADE_WS_ERROR.getMessage(), symbol, e.getMessage());
                session.addLog(now() + " [오류] " + e.getMessage());
            }
        }
    }

    /**
     * 마감된 1분봉의 신호를 세션 내 각 큐에 적용한다.
     * 큐가 BLOCK_MATCHING 상태이고 현재 블록이 조건 블록(leaf 아님)이며
     * blockBaseTime이 이 봉 구간에 속할 때만 신호를 처리한다.
     */
    private void applyKlineSignal(AutoTradeSessionDTO session,
                                  WebSocketKlineDTO.KlineData kline, Side signal) {
        long startMs = kline.getStart();
        long endMs = kline.getEnd();

        for (PatternQueue queue : new ArrayList<>(session.getQueues())) {
            QueueStateDTO state = session.getQueueStates().get(queue.getId());
            if (state == null) continue;
            if (state.getPhase() != TradePhase.BLOCK_MATCHING) continue;
            if (state.getBlockBaseTime() == null) continue;

            // 큐의 진입 시각이 이 봉 구간에 속하는지 확인
            long blockBaseMs = state.getBlockBaseTime()
                    .atZone(java.time.ZoneId.systemDefault())
                    .toInstant().toEpochMilli();
            if (blockBaseMs < startMs || blockBaseMs > endMs) continue;

            Pattern pattern = findPatternById(queue, state.getActivePatternId());
            if (pattern == null) continue;
            PatternBlock currentBlock = findBlockByOrder(pattern, state.getCurrentBlockOrder());
            if (currentBlock == null || currentBlock.isLeaf()) continue; // leaf는 push 기반 진입에서 처리

            if (!state.tryLock()) continue;
            try {
                if (signal == null) {
                    // 도지: 기준 시각만 다음 봉으로 리셋 (다음 1분봉 마감 시 재판정)
                    state.setBlockBaseTime(LocalDateTime.now());
                    state.setBlockBasePrice(kline.getClose());
                    session.addLog(now() + " [블록 매칭] 도지(close==open) → 다음 봉 재대기 (큐 #"
                            + queue.getId() + ")");
                    continue;
                }

                if (signal == currentBlock.getSide()) {
                    // 신호 일치 → 다음 블록으로 이동
                    state.setCurrentBlockOrder(state.getCurrentBlockOrder() + 1);
                    state.setBlockBaseTime(LocalDateTime.now());
                    state.setBlockBasePrice(kline.getClose());
                    session.addLog(now() + " [블록 매칭] " + signal + " 일치 → 블록 "
                            + state.getCurrentBlockOrder() + " (큐 #" + queue.getId() + ")");
                } else {
                    // 신호 반대 → 청산 없이 다음 단계로 (포지션 미보유)
                    handleNextStep(queue, state, session, signal);
                }
            } finally {
                state.unlock();
            }
        }
    }

    /**
     * 패턴 활성화 상태 변경 시 자동매매 세션을 동기화한다.
     * 활성 큐가 1개 이상이면 세션을 생성/갱신하고, 0개이면 세션을 제거한다.
     * WebSocket 구독도 함께 동기화한다.
     *
     * @param userId    사용자 ID
     * @param symbol    코인 심볼
     * @param tradeMode 거래 모드 (MAIN/SIM)
     */
    public void syncSession(Long userId, String symbol, TradeMode tradeMode) {
        // isActive=true인 패턴 큐만 조회 (거래 모드별 분리)
        List<PatternQueue> activeQueues = patternQueueRepository
                .findByUserIdAndSymbolAndIsActiveAndTradeModeOrderByCreatedAtAsc(userId, symbol, true, tradeMode);

        String key = sessionKey(userId, symbol, tradeMode);

        if (activeQueues.isEmpty()) {
            if (activeSessions.remove(key) != null) {
                log.info(LogMessage.AUTO_TRADE_STOPPED.getMessage(), userId, symbol);
            }
        } else {
            AutoTradeSessionDTO existing = activeSessions.get(key);
            if (existing != null) {
                // 기존 세션 갱신: 큐 목록 업데이트 + 새 큐의 상태 초기화
                existing.setQueues(activeQueues);
                syncQueueStates(existing, activeQueues);
                existing.addLog(now() + " [갱신] 활성 큐 " + activeQueues.size() + "개로 갱신");
                log.info(LogMessage.AUTO_TRADE_SESSION_REFRESHED.getMessage(), userId, symbol, activeQueues.size());
            } else {
                // 신규 세션 생성
                AutoTradeSessionDTO session = new AutoTradeSessionDTO();
                session.setUserId(userId);
                session.setSymbol(symbol);
                session.setTradeMode(tradeMode);
                session.setQueues(activeQueues);
                // 각 큐에 대해 초기 상태 생성
                syncQueueStates(session, activeQueues);
                activeSessions.put(key, session);
                log.info(LogMessage.AUTO_TRADE_STARTED.getMessage(), userId, symbol, activeQueues.size());
            }
        }

        // WebSocket 구독 동기화
        syncWebSocketSubscriptions();
    }

    /**
     * 자동매매 활성 상태를 확인한다.
     *
     * @param userId 사용자 ID
     * @param symbol 코인 심볼
     * @return 활성 세션이 존재하면 true
     */
    public boolean isActive(Long userId, String symbol, TradeMode tradeMode) {
        return activeSessions.containsKey(sessionKey(userId, symbol, tradeMode));
    }

    /**
     * 자동매매 세션 정보를 반환한다.
     *
     * @param userId    사용자 ID
     * @param symbol    코인 심볼
     * @param tradeMode 거래 모드
     * @return 활성 세션 DTO, 존재하지 않으면 null
     */
    public AutoTradeSessionDTO getSession(Long userId, String symbol, TradeMode tradeMode) {
        return activeSessions.get(sessionKey(userId, symbol, tradeMode));
    }


    /**
     * 자동매매 상태 응답을 생성한다.
     * WebSocket 실시간 가격을 기반으로 트리거 경과시간/변동률을 계산한다.
     *
     * @param userId    사용자 ID
     * @param symbol    코인 심볼
     * @param tradeMode 거래 모드
     * @return 자동매매 상태 응답
     */
    public AutoTradeStatusResponse getStatusResponse(Long userId, String symbol, TradeMode tradeMode) {
        if (!isActive(userId, symbol, tradeMode)) {
            return AutoTradeStatusResponse.builder()
                    .active(false)
                    .build();
        }

        AutoTradeSessionDTO session = getSession(userId, symbol, tradeMode);
        if (session.getQueues().isEmpty()) {
            return AutoTradeStatusResponse.builder().active(false).build();
        }

        // 첫 번째 활성 큐의 상태를 대표로 반환
        PatternQueue firstQueue = session.getQueues().get(0);
        QueueStateDTO state = session.getQueueStates().get(firstQueue.getId());

        // WebSocket 실시간 가격 조회
        long elapsedSeconds = 0;
        long blockRemainingSeconds = 0;
        BigDecimal changeRate = null;
        BigDecimal amount = null;
        int activePatternOrder = 0;
        String entryPrice = null; // 진입 시점 체결가 (포지션 보유 중일 때 세팅)
        BigDecimal tpPrice = null; // 익절가 (POSITION_HOLDING 시 노출)
        BigDecimal slPrice = null; // 손절가 (POSITION_HOLDING 시 노출)

        FindTickerResponse.TickerInfo wsTicker = marketService.getWsTicker(symbol);
        String currentPrice = wsTicker != null ? wsTicker.getLastPrice() : null;

        if (state != null && currentPrice != null) {
            // TRIGGER_WAIT: 경과시간 + 변동률 (기준가 대비)
            if (state.getPhase() == TradePhase.TRIGGER_WAIT && state.getBasePrice() != null) {
                if (state.getBaseTime() != null) {
                    elapsedSeconds = Duration.between(state.getBaseTime(), LocalDateTime.now()).getSeconds();
                }
                changeRate = calculateTriggerRate(state.getBasePrice(), currentPrice);
            }
            // BLOCK_MATCHING: 다음 1분봉 완성 시점까지의 잔여 대기 시간
            else if (state.getPhase() == TradePhase.BLOCK_MATCHING) {
                if (state.getBlockBaseTime() != null) {
                    LocalDateTime candleCloseAt = state.getBlockBaseTime()
                            .truncatedTo(ChronoUnit.MINUTES)
                            .plusMinutes(1);
                    blockRemainingSeconds = Math.max(0,
                            Duration.between(LocalDateTime.now(), candleCloseAt).getSeconds());
                } else {
                    blockRemainingSeconds = BLOCK_WAIT_SECONDS; // 아직 기준 시각 미설정 — UI 초기값
                }
                // 활성 패턴에서 투입 금액 조회
                Pattern activePattern = findPatternById(firstQueue, state.getActivePatternId());
                if (activePattern != null) {
                    amount = activePattern.getAmount();
                    activePatternOrder = activePattern.getPatternOrder();
                }
            }
            // POSITION_HOLDING: 진입가/익절가/손절가/현재 변동률
            else if (state.getPhase() == TradePhase.POSITION_HOLDING) {
                if (state.getEntryPrice() != null) {
                    changeRate = calculateTriggerRate(state.getEntryPrice(), currentPrice);
                    entryPrice = state.getEntryPrice();
                }
                tpPrice = state.getTpPrice();
                slPrice = state.getSlPrice();
                Pattern activePattern = findPatternById(firstQueue, state.getActivePatternId());
                if (activePattern != null) {
                    amount = activePattern.getAmount();
                    activePatternOrder = activePattern.getPatternOrder();
                }
            }
        }

        return AutoTradeStatusResponse.builder()
                .active(true)
                .currentQueueId(firstQueue.getId())
                .currentStepLevel(state != null ? state.getCurrentStepLevel() : 0)
                .phase(state != null ? state.getPhase().name() : null)
                .direction(state != null && state.getDirection() != null ? state.getDirection().name() : null)
                .currentBlockOrder(state != null ? state.getCurrentBlockOrder() : 0)
                .activePatternOrder(activePatternOrder)
                .elapsedSeconds(elapsedSeconds)
                .blockRemainingSeconds(blockRemainingSeconds)
                .changeRate(changeRate)
                .amount(amount)
                .entryPrice(entryPrice)
                .tpPrice(tpPrice)
                .slPrice(slPrice)
                .build();
    }


    /**
     * 기준비율로부터 변동된 가격 비율을 반환한다.
     * @param basePrice 활성화된 시점의 가격
     * @param currentPrice 현재 가격
     * @return 기준금액과 현재 금액의 차이(비율)
     */
    public BigDecimal calculateTriggerRate(String basePrice, String currentPrice){
        BigDecimal base = new BigDecimal(basePrice);
        BigDecimal current = new BigDecimal(currentPrice);
        return  current.subtract(base)
                .divide(base, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    /**
     * 1초 주기로 실행되는 자동매매 안전망(fallback).
     * WebSocket 장애 시에도 REST 폴링으로 매매가 지속되도록 한다.
     */
    @Scheduled(fixedRate = 1000)
    public void tick() {
        if (activeSessions.isEmpty()) {
            return;
        }

        FindTickerResponse tickerResponse = marketService.getTickers();
        if (tickerResponse == null || tickerResponse.getResult() == null
                || tickerResponse.getResult().getList() == null) {
            return;
        }

        List<FindTickerResponse.TickerInfo> tickers = tickerResponse.getResult().getList();

        for (AutoTradeSessionDTO session : new ArrayList<>(activeSessions.values())) {
            try {
                processSession(session, tickers);
            } catch (Exception e) {
                log.error(LogMessage.AUTO_TRADE_TICK_ERROR.getMessage(), session.getSymbol(), e.getMessage());
                session.addLog(now() + " [오류] " + e.getMessage());
            }
        }
    }

    // ─────────────────────────────────────────────
    // 세션 처리 (WebSocket / REST fallback)
    // ─────────────────────────────────────────────

    /**
     * WebSocket 가격으로 세션 내 각 큐를 독립적으로 처리한다.
     */
    private void processSessionWithPrice(AutoTradeSessionDTO session, String currentPrice) {
        if (currentPrice == null) {
            return;
        }
        // 각 큐를 독립적으로 처리
        for (PatternQueue queue : new ArrayList<>(session.getQueues())) {
            QueueStateDTO state = session.getQueueStates().get(queue.getId());
            if (state == null) {
                continue;
            }
            processQueue(queue, state, session, currentPrice);
        }
    }

    /**
     * REST 폴링 티커로 세션 내 각 큐를 독립적으로 처리한다. (fallback용)
     */
    private void processSession(AutoTradeSessionDTO session, List<FindTickerResponse.TickerInfo> tickers) {
        // 해당 심볼의 현재가 조회
        String currentPrice = null;
        for (FindTickerResponse.TickerInfo ticker : tickers) {
            if (ticker.getSymbol().equals(session.getSymbol())) {
                currentPrice = ticker.getLastPrice();
                break;
            }
        }

        if (currentPrice == null) {
            return;
        }

        // 각 큐를 독립적으로 처리
        for (PatternQueue queue : new ArrayList<>(session.getQueues())) {
            QueueStateDTO state = session.getQueueStates().get(queue.getId());
            if (state == null) {
                continue;
            }
            processQueue(queue, state, session, currentPrice);
        }
    }

    /**
     * 개별 큐의 현재 페이즈에 따라 적절한 처리를 수행한다.
     */
    private void processQueue(PatternQueue queue, QueueStateDTO state,
                              AutoTradeSessionDTO session, String currentPrice) {
        // 중복 실행 방지: 이미 처리 중이면 스킵
        if (!state.tryLock()) return;

        try {
            // 페이즈별 분기 처리
            switch (state.getPhase()) {
                case TRIGGER_WAIT -> processTriggerWait(queue, state, session, currentPrice);
                // POSITION_OPEN은 트리거 → BLOCK_MATCHING 전환 중간 상태로만 사용 (즉시 BLOCK_MATCHING으로 이동)
                case POSITION_OPEN -> state.setPhase(TradePhase.BLOCK_MATCHING);
                case BLOCK_MATCHING -> processBlockMatching(queue, state, session, currentPrice);
                case POSITION_HOLDING -> processPositionHolding(queue, state, session, currentPrice);
            }
        } finally {
            state.unlock();
        }
    }

    // ─────────────────────────────────────────────
    // Phase 1: 트리거 대기 (SRS 9)
    // ─────────────────────────────────────────────

    /**
     * 트리거 조건을 확인하여 매매 방향을 결정한다.
     * 큐 활성화 시점 기준가 대비 triggerRate% 이상 변동 시 즉시 방향 결정한다.
     *
     * @param queue        현재 큐
     * @param state        큐 런타임 상태
     * @param session      세션
     * @param currentPrice 현재가
     */
    void processTriggerWait(PatternQueue queue, QueueStateDTO state,
                            AutoTradeSessionDTO session, String currentPrice) {
        // 최초 호출 시 기준가 설정
        if (state.getBasePrice() == null) {
            state.setBasePrice(currentPrice);
            state.setBaseTime(LocalDateTime.now());
            session.addLog(now() + " [트리거 대기] 큐 #" + queue.getId()
                    + " 기준가: " + currentPrice);
            return;
        }

        // 변동률 계산
        BigDecimal changeRate = this.calculateTriggerRate(state.getBasePrice(), currentPrice);
        BigDecimal triggerRate = queue.getTriggerRate();

        // 상승률 충족 → LONG
        if (changeRate.compareTo(triggerRate) >= 0) {
            state.setDirection(Side.LONG);
            transitionToBlockMatching(queue, state, session);
            session.addLog(now() + " [트리거 충족] 큐 #" + queue.getId()
                    + " 방향: LONG (변동률: " + changeRate.setScale(2, RoundingMode.HALF_UP) + "%)");
            return;
        }

        // 하락률 충족 → SHORT
        if (changeRate.compareTo(triggerRate.negate()) <= 0) {
            state.setDirection(Side.SHORT);
            transitionToBlockMatching(queue, state, session);
            session.addLog(now() + " [트리거 충족] 큐 #" + queue.getId()
                    + " 방향: SHORT (변동률: " + changeRate.setScale(2, RoundingMode.HALF_UP) + "%)");
        }
    }

    /**
     * 트리거 충족 후 BLOCK_MATCHING 페이즈로 전환한다.
     * 현재 단계에서 방향에 맞는 패턴을 선택하고, 블록 기준 시각/가격을 초기화한다.
     */
    private void transitionToBlockMatching(PatternQueue queue, QueueStateDTO state,
                                           AutoTradeSessionDTO session) {
        PatternStep currentStep = findStepByLevel(queue, state.getCurrentStepLevel());
        if (currentStep == null) {
            deactivateQueue(queue, state, session, "단계 " + state.getCurrentStepLevel() + " 없음");
            return;
        }

        Pattern selectedPattern = selectPattern(currentStep, state.getDirection());
        if (selectedPattern == null) {
            deactivateQueue(queue, state, session,
                    state.getCurrentStepLevel() + "단계에 " + state.getDirection() + " 패턴 없음");
            return;
        }

        state.setActiveStepId(currentStep.getId());
        state.setActivePatternId(selectedPattern.getId());
        // 첫 블록은 트리거 신호로 자동 매칭된 것으로 간주 → 두 번째 블록부터 매칭 시작
        state.setCurrentBlockOrder(2);
        // 첫 블록 기준 시각/가격 초기화 (determineSignal에서 설정됨)
        state.setBlockBaseTime(null);
        state.setBlockBasePrice(null);
        state.setPhase(TradePhase.BLOCK_MATCHING);
    }

    // ─────────────────────────────────────────────
    // Phase 2 (비활성): processPositionOpen은 더 이상 사용하지 않음
    // 트리거 충족 시 transitionToBlockMatching()으로 직접 BLOCK_MATCHING 진입
    // 포지션 진입은 leaf 직전 블록(N-1) 매칭 시 openPosition()에서 실행
    // ─────────────────────────────────────────────

    /**
     * @deprecated 더 이상 사용하지 않음. processQueue의 POSITION_OPEN 케이스에서 즉시 BLOCK_MATCHING으로 전환.
     */
    @Deprecated
    void processPositionOpen(PatternQueue queue, QueueStateDTO state,
                             AutoTradeSessionDTO session, String currentPrice) {
        // 이미 포지션이 열려있으면 이중 진입 방지 (상태 불일치 보정)
        if (state.getEntryPrice() != null) {
            log.warn("[이중 진입 감지] entryPrice={}, queueId={}", state.getEntryPrice(), queue.getId());
            state.setPhase(TradePhase.BLOCK_MATCHING);
            return;
        }

        Pattern pattern = findPatternById(queue, state.getActivePatternId());
        if (pattern == null) {
            session.addLog(now() + " [오류] 패턴을 찾을 수 없음: " + state.getActivePatternId());
            return;
        }

        String side = state.getDirection() == Side.LONG ? "Buy" : "Sell";
        String leverageStr = String.valueOf(pattern.getLeverage());

        // 마진 모드를 Isolated로 전환 (Cross 모드 진입 방지)
        try {
            tradeFacade.switchToIsolated(session.getUserId(), session.getTradeMode());
        } catch (Exception e) {
            // Isolated 전환 실패 시 Cross 모드로 주문 방지 → 큐 비활성화
            session.addLog(now() + " [진입 실패] 마진 모드 Isolated 전환 실패 : " + e.getMessage());
            log.error(LogMessage.MARGIN_MODE_SWITCH_FAILED.getMessage(), queue.getId(), e.getMessage());
            deactivateQueue(queue, state, session, "마진 모드 전환 실패: " + e.getMessage());
            return;
        }

        // 레버리지 설정
        try {
            SetLeverageRequest leverageRequest = new SetLeverageRequest(
                    Category.LINEAR.getCategory(),
                    session.getSymbol(),
                    leverageStr,
                    leverageStr
            );
            tradeFacade.setLeverage(leverageRequest, session.getUserId(), session.getTradeMode());
        } catch (Exception e) {
            // 이미 동일 레버리지가 설정된 경우 Bybit이 에러를 반환할 수 있으므로 경고만 로그
            log.warn(LogMessage.LEVERAGE_SET_FAILED.getMessage(), e.getMessage());
        }

        // USDT 금액을 코인 수량으로 변환 (Linear 선물은 코인 수량 단위로 주문)
        // notional = 마진(amount) × 레버리지 → 실제 포지션 크기 기준으로 수량 계산
        BigDecimal notional = pattern.getAmount().multiply(BigDecimal.valueOf(pattern.getLeverage()));
        String qty = marketService.convertUsdtToQty(
                session.getSymbol(), notional, new BigDecimal(currentPrice));
        if (qty == null) {
            // qtyStep 캐시 미스 → 진짜 오류, 큐 비활성화
            session.addLog(now() + " [진입 실패] qtyStep 조회 실패: " + session.getSymbol());
            deactivateQueue(queue, state, session, "수량 변환 실패");
            return;
        }
        if ("0".equals(qty)) {
            // 금액 대비 현재가가 너무 높아 최소 주문 단위 미달 → 이번 틱 스킵 (가격 하락 시 자동 해결)
            session.addLog(now() + " [진입 스킵] 최소 주문 단위 미달: " + session.getSymbol()
                    + " " + pattern.getAmount() + "USDT, 현재가=" + currentPrice);
            return;
        }

        // 시장가 주문 실행
        CreateOrderRequest orderRequest = CreateOrderRequest.builder()
                .category(Category.LINEAR.getCategory())
                .symbol(session.getSymbol())
                .side(side)
                .orderType("Market")
                .qty(qty)
                .build();

        // TradeHistory 준비 (투입 금액은 마진 기준으로 저장)
        TradeHistory history = new TradeHistory();
        history.setQueueStepId(state.getActiveStepId());
        history.setUserId(session.getUserId());
        history.setSymbol(session.getSymbol());
        history.setSide(state.getDirection());
        history.setOrderType(TradeOrderType.ENTRY.getTradeOrderType());
        history.setAmount(pattern.getAmount()); // 마진(실제 투자금) 저장

        // 진입 수수료 계산: qty × 진입가 × taker 요율 (SIM 지갑 차감 및 히스토리 기록용)
        BigDecimal entryFee = new BigDecimal(qty)
                .multiply(new BigDecimal(currentPrice))
                .multiply(TAKER_FEE_RATE)
                .setScale(4, RoundingMode.HALF_UP);
        history.setFee(entryFee);

        // 주문 전 진입가 선점: 이중 진입 방지 (상단 가드와 함께 이중 안전장치)
        state.setEntryPrice(currentPrice);

        try {
            // 주문 실행 (실패 시 TradeService에서 history에 실패 이력 저장 후 예외)
            tradeFacade.placeOrder(orderRequest, session.getUserId(), history, session.getTradeMode());

            // 주문 성공: 이력 저장 (모드에 따라 테이블 분기)
            history.setExecutedPrice(new BigDecimal(currentPrice));
            history.setOrderResult(OrderResult.SUCCESS);
            saveTradeHistory(history, session.getTradeMode());

            state.setEntryQty(qty);                        // 매도/청산 시 포지션 전량 청산을 위해 진입 수량 저장
            state.setEntryMargin(pattern.getAmount());     // 마진 기억 (투자 히스토리 원금 기준)
            state.setCloseSkipCount(0);                    // 새 포지션 진입 시 스킵 카운터 초기화

            session.addLog(now() + " [진입] " + side + " "
                    + pattern.getAmount().stripTrailingZeros().toPlainString() + "$ "
                    + session.getSymbol() + " (큐 #" + queue.getId()
                    + ", " + state.getCurrentStepLevel() + "단계, x" + pattern.getLeverage() + ")");

            // BLOCK_MATCHING 전환 (첫 조건 블록은 진입 방향으로 이미 소비)
            state.setCurrentBlockOrder(2);
            state.setPhase(TradePhase.BLOCK_MATCHING);

        } catch (Exception e) {
            // 주문 실패: 진입가 선점 해제 후 큐 비활성화 (TradeService에서 이미 history 저장 완료)
            state.setEntryPrice(null);
            session.addLog(now() + " [진입 실패] " + e.getMessage());
            log.error(LogMessage.POSITION_ENTRY_FAILED.getMessage(), queue.getId(), e.getMessage());
            deactivateQueue(queue, state, session, "주문 실패: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    // Phase 3: 블록 매칭 (SRS 11-14, 17)
    // ─────────────────────────────────────────────

    /**
     * 가격 push 시 호출되는 블록 매칭 처리.
     * - leaf 블록 도달 → 즉시 진입 (POSITION_HOLDING으로 전환)
     * - 조건 블록 → blockBaseTime/blockBasePrice만 세팅하고 1분봉 마감 push({@link #onKlineConfirmed})를 기다림
     *
     * <p>조건 블록의 신호 판단은 가격 push 시점이 아니라 거래소가 1분봉 마감을 알리는 시점에 이루어진다.
     * 매도/청산은 {@link #processPositionHolding}에서 TP/SL 가격으로 트리거된다.</p>
     *
     * @param queue        현재 큐
     * @param state        큐 런타임 상태
     * @param session      세션
     * @param currentPrice 현재가
     */
    void processBlockMatching(PatternQueue queue, QueueStateDTO state,
                              AutoTradeSessionDTO session, String currentPrice) {
        Pattern pattern = findPatternById(queue, state.getActivePatternId());
        if (pattern == null) return;

        PatternBlock currentBlock = findBlockByOrder(pattern, state.getCurrentBlockOrder());
        if (currentBlock == null) return;

        // leaf 블록 도달 → 즉시 진입 (포지션 방향은 leaf의 side)
        if (currentBlock.isLeaf()) {
            openPosition(queue, state, session, pattern, currentPrice);
            return;
        }

        // 조건 블록: 관찰 시작 시각만 한 번 기록 — 신호 판단은 1분봉 마감 push에서
        if (state.getBlockBaseTime() == null) {
            state.setBlockBaseTime(LocalDateTime.now());
            state.setBlockBasePrice(currentPrice);
            session.addLog(now() + " [블록 관찰] 1분봉 마감 대기 - 블록 "
                    + state.getCurrentBlockOrder() + " (큐 #" + queue.getId() + ")");
        }
    }

    /**
     * leaf 블록 도달 시 포지션을 진입한다.
     * 레버리지 설정 → 시장가 주문 → TP/SL 가격 계산 → POSITION_HOLDING 페이즈로 전환.
     *
     * @param queue        현재 큐
     * @param state        큐 런타임 상태
     * @param session      세션
     * @param pattern      현재 패턴 (레버리지, 금액, TP/SL 비율)
     * @param currentPrice 현재가 (진입가로 사용)
     */
    private void openPosition(PatternQueue queue, QueueStateDTO state,
                              AutoTradeSessionDTO session, Pattern pattern, String currentPrice) {
        // 이중 진입 방지
        if (state.getEntryPrice() != null) {
            log.warn("[이중 진입 감지] entryPrice={}, queueId={}", state.getEntryPrice(), queue.getId());
            return;
        }

        // 진입 방향은 leaf 블록의 side로 결정 (트리거 신호 방향이 아님)
        // 예: S:L 패턴 → 트리거는 SHORT지만 진입은 LONG
        PatternBlock leafBlock = pattern.getBlocks().stream()
                .filter(PatternBlock::isLeaf)
                .findFirst()
                .orElse(null);
        if (leafBlock == null) {
            session.addLog(now() + " [진입 실패] leaf 블록 없음: pattern=" + pattern.getId());
            deactivateQueue(queue, state, session, "leaf 블록 없음");
            return;
        }
        Side entryDirection = leafBlock.getSide();

        String side = entryDirection == Side.LONG ? "Buy" : "Sell";
        String leverageStr = String.valueOf(pattern.getLeverage());

        // 마진 모드 Isolated 전환
        try {
            tradeFacade.switchToIsolated(session.getUserId(), session.getTradeMode());
        } catch (Exception e) {
            session.addLog(now() + " [진입 실패] 마진 모드 Isolated 전환 실패: " + e.getMessage());
            log.error(LogMessage.MARGIN_MODE_SWITCH_FAILED.getMessage(), queue.getId(), e.getMessage());
            deactivateQueue(queue, state, session, "마진 모드 전환 실패: " + e.getMessage());
            return;
        }

        // 레버리지 설정
        try {
            SetLeverageRequest leverageRequest = new SetLeverageRequest(
                    Category.LINEAR.getCategory(), session.getSymbol(), leverageStr, leverageStr);
            tradeFacade.setLeverage(leverageRequest, session.getUserId(), session.getTradeMode());
        } catch (Exception e) {
            log.warn(LogMessage.LEVERAGE_SET_FAILED.getMessage(), e.getMessage());
        }

        // 수량 계산 (notional = 마진 × 레버리지)
        BigDecimal notional = pattern.getAmount().multiply(BigDecimal.valueOf(pattern.getLeverage()));
        String qty = marketService.convertUsdtToQty(session.getSymbol(), notional, new BigDecimal(currentPrice));
        if (qty == null) {
            session.addLog(now() + " [진입 실패] qtyStep 조회 실패: " + session.getSymbol());
            deactivateQueue(queue, state, session, "수량 변환 실패");
            return;
        }
        if ("0".equals(qty)) {
            session.addLog(now() + " [진입 스킵] 최소 주문 단위 미달: " + session.getSymbol()
                    + " " + pattern.getAmount() + "USDT, 현재가=" + currentPrice);
            return;
        }

        CreateOrderRequest orderRequest = CreateOrderRequest.builder()
                .category(Category.LINEAR.getCategory())
                .symbol(session.getSymbol())
                .side(side)
                .orderType("Market")
                .qty(qty)
                .build();

        TradeHistory history = new TradeHistory();
        history.setQueueStepId(state.getActiveStepId());
        history.setUserId(session.getUserId());
        history.setSymbol(session.getSymbol());
        history.setSide(entryDirection); // leaf side = 실제 포지션 방향
        history.setOrderType(TradeOrderType.ENTRY.getTradeOrderType());
        history.setAmount(pattern.getAmount()); // 마진 저장

        BigDecimal entryFee = new BigDecimal(qty)
                .multiply(new BigDecimal(currentPrice))
                .multiply(TAKER_FEE_RATE)
                .setScale(4, RoundingMode.HALF_UP);
        history.setFee(entryFee);

        // 이중 진입 방지: 주문 전 진입가 선점
        state.setEntryPrice(currentPrice);

        try {
            tradeFacade.placeOrder(orderRequest, session.getUserId(), history, session.getTradeMode());

            history.setExecutedPrice(new BigDecimal(currentPrice));
            history.setOrderResult(OrderResult.SUCCESS);
            saveTradeHistory(history, session.getTradeMode());

            state.setEntryQty(qty);
            state.setEntryMargin(pattern.getAmount());
            state.setCloseSkipCount(0);
            // 진입 후 state.direction을 leaf side로 갱신 — 이후 흐름(POSITION_HOLDING TP/SL 비교,
            // handleSellSuccess 1단계 재진입 등)이 실제 포지션 방향 기준으로 동작하도록 정합성 보장
            state.setDirection(entryDirection);

            // 익절/손절 가격 계산 (pattern.takeProfitRate / stopLossRate 우선, 미설정 시 100/leverage 기본값)
            BigDecimal entry = new BigDecimal(currentPrice);
            int leverage = pattern.getLeverage();
            BigDecimal defaultThreshold = BigDecimal.valueOf(100.0 / leverage);
            BigDecimal tpRate = pattern.getTakeProfitRate() != null ? pattern.getTakeProfitRate() : defaultThreshold;
            BigDecimal slRate = pattern.getStopLossRate()   != null ? pattern.getStopLossRate()   : defaultThreshold;
            BigDecimal hundred = BigDecimal.valueOf(100);
            BigDecimal tpPrice;
            BigDecimal slPrice;
            // LONG: 가격 상승 시 익절 / SHORT: 가격 하락 시 익절
            if (entryDirection == Side.LONG) {
                tpPrice = entry.multiply(BigDecimal.ONE.add(tpRate.divide(hundred, 6, RoundingMode.HALF_UP)));
                slPrice = entry.multiply(BigDecimal.ONE.subtract(slRate.divide(hundred, 6, RoundingMode.HALF_UP)));
            } else {
                tpPrice = entry.multiply(BigDecimal.ONE.subtract(tpRate.divide(hundred, 6, RoundingMode.HALF_UP)));
                slPrice = entry.multiply(BigDecimal.ONE.add(slRate.divide(hundred, 6, RoundingMode.HALF_UP)));
            }
            state.setTpPrice(tpPrice);
            state.setSlPrice(slPrice);

            session.addLog(now() + " [진입] " + side + " "
                    + pattern.getAmount().stripTrailingZeros().toPlainString() + "$ "
                    + session.getSymbol() + " (큐 #" + queue.getId()
                    + ", " + state.getCurrentStepLevel() + "단계, x" + pattern.getLeverage()
                    + ", TP=" + tpPrice.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
                    + ", SL=" + slPrice.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + ")");

            // POSITION_HOLDING 페이즈로 전환 (이후 실시간 가격 vs TP/SL 비교)
            state.setPhase(TradePhase.POSITION_HOLDING);

        } catch (Exception e) {
            state.setEntryPrice(null);
            session.addLog(now() + " [진입 실패] " + e.getMessage());
            log.error(LogMessage.POSITION_ENTRY_FAILED.getMessage(), queue.getId(), e.getMessage());
            deactivateQueue(queue, state, session, "주문 실패: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    // Phase 4: 포지션 보유 중 (TP/SL 모니터링)
    // ─────────────────────────────────────────────

    /**
     * 포지션 보유 중 실시간 가격을 TP/SL 가격과 비교하여 매도/청산을 트리거한다.
     * - LONG: current >= tpPrice → 매도 / current <= slPrice → 청산
     * - SHORT: current <= tpPrice → 매도 / current >= slPrice → 청산
     *
     * <p>매도(handleSellSuccess) → 1단계 같은 방향 패턴 재진입.
     * 청산(handleLiquidation) → 다음 단계, 손절 방향 신호로 패턴 선택.</p>
     *
     * @param queue        현재 큐
     * @param state        큐 런타임 상태 (tpPrice, slPrice 진입 시점에 세팅됨)
     * @param session      세션
     * @param currentPrice 현재가 (WebSocket push 또는 1초 fallback)
     */
    void processPositionHolding(PatternQueue queue, QueueStateDTO state,
                                AutoTradeSessionDTO session, String currentPrice) {
        // 방어 코드: TP/SL 미세팅 (정상 흐름에서는 발생하지 않음)
        if (state.getTpPrice() == null || state.getSlPrice() == null) {
            log.warn("[POSITION_HOLDING] TP/SL 미설정 - queueId={}", queue.getId());
            return;
        }

        Pattern pattern = findPatternById(queue, state.getActivePatternId());
        if (pattern == null) return;

        BigDecimal current = new BigDecimal(currentPrice);

        if (state.getDirection() == Side.LONG) {
            // LONG 익절: 가격 상승해 TP 도달
            if (current.compareTo(state.getTpPrice()) >= 0) {
                handleSellSuccess(queue, state, session, pattern, currentPrice);
            }
            // LONG 손절: 가격 하락해 SL 도달 → 반대(SHORT) 방향 신호로 다음 단계 이동
            else if (current.compareTo(state.getSlPrice()) <= 0) {
                handleLiquidation(queue, state, session, pattern, Side.SHORT, currentPrice);
            }
        } else {
            // SHORT 익절: 가격 하락해 TP 도달
            if (current.compareTo(state.getTpPrice()) <= 0) {
                handleSellSuccess(queue, state, session, pattern, currentPrice);
            }
            // SHORT 손절: 가격 상승해 SL 도달 → 반대(LONG) 방향 신호로 다음 단계 이동
            else if (current.compareTo(state.getSlPrice()) >= 0) {
                handleLiquidation(queue, state, session, pattern, Side.LONG, currentPrice);
            }
        }
    }

    /**
     * 조건 블록에서 반대 신호 발생 시 청산 없이 다음 단계로만 이동한다.
     * (포지션을 보유하지 않은 상태이므로 실제 주문 없음)
     *
     * @param queue         현재 큐
     * @param state         큐 런타임 상태
     * @param session       세션
     * @param nextDirection 반대 신호 방향 (다음 단계 패턴 선택 기준)
     */
    private void handleNextStep(PatternQueue queue, QueueStateDTO state,
                                AutoTradeSessionDTO session, Side nextDirection) {
        int nextStepLevel = state.getCurrentStepLevel() + 1;
        PatternStep nextStep = findStepByLevel(queue, nextStepLevel);

        if (nextStep == null) {
            deactivateQueue(queue, state, session, "모든 단계 소진");
            return;
        }

        Pattern nextPattern = selectPattern(nextStep, nextDirection);
        if (nextPattern == null) {
            deactivateQueue(queue, state, session,
                    nextStepLevel + "단계에 " + nextDirection + " 패턴 없음");
            return;
        }

        state.setCurrentStepLevel(nextStepLevel);
        state.setActiveStepId(nextStep.getId());
        state.setActivePatternId(nextPattern.getId());
        state.setDirection(nextDirection);
        // 첫 블록은 직전 신호(반대 방향 포착)로 자동 매칭된 것으로 간주 → 두 번째 블록부터 매칭
        state.setCurrentBlockOrder(2);
        state.setBlockBaseTime(null);
        state.setBlockBasePrice(null);
        // phase는 BLOCK_MATCHING 유지 (포지션 없음)

        session.addLog(now() + " [다음 단계] 블록 불일치 → " + nextStepLevel + "단계 "
                + nextDirection + " (큐 #" + queue.getId() + ")");
    }

    // [제거] determineSignal: 1분봉 신호 판단은 거래소가 push하는 onKlineConfirmed에서 직접 처리한다.

    /**
     * 매도 성공 처리 (SRS 11, 12): 시장가 매도 → 1단계로 리셋 후 매도 성공한 방향으로 재진입
     * - 매도 성공 시 항상 1단계부터 다시 수행 (SRS 11)
     * - 재진입 방향은 매도 성공한 포지션 방향 그대로 유지 (SRS 12)
     */
    private void handleSellSuccess(PatternQueue queue, QueueStateDTO state,
                                   AutoTradeSessionDTO session,
                                   Pattern pattern, String currentPrice) {
        // 시장가 매도 실행 (진입 방향의 반대)
        String closeSide = state.getDirection() == Side.LONG ? "Sell" : "Buy";

        try {
            // 진입 시 저장한 qty 사용 (현재가 재계산 시 포지션 일부만 청산되는 문제 방지)
            String closeQty = state.getEntryQty();
            if (closeQty == null || "0".equals(closeQty)) {
                // 진입 수량 정보 없음 → 스킵 횟수 제한 적용
                int skipCount = state.getCloseSkipCount() + 1;
                state.setCloseSkipCount(skipCount);
                session.addLog(now() + " [매도 스킵] 청산 수량 확인 불가 (" + skipCount + "/5): " + session.getSymbol());
                if (skipCount >= 5) {
                    log.warn(LogMessage.CLOSE_QTY_SKIP_DEACTIVATED.getMessage(), queue.getId(), "SELL");
                    session.addLog(now() + " [매도 중단] 청산 수량을 5회 연속 확인할 수 없어 큐를 비활성화합니다."
                            + " 열린 포지션을 수동으로 확인해 주세요.");
                    deactivateQueue(queue, state, session, "청산 수량 오류 5회 초과");
                }
                return;
            }

            TradeHistory history = new TradeHistory();
            history.setQueueStepId(state.getActiveStepId());
            history.setUserId(session.getUserId());
            history.setSymbol(session.getSymbol());
            history.setSide(state.getDirection());
            history.setOrderType(TradeOrderType.SELL.getTradeOrderType());
            history.setAmount(state.getEntryMargin()); // 마진(실제 투자금) 저장

            // 매도 수수료 계산: closeQty × 체결가 × taker 요율
            BigDecimal exitFee = new BigDecimal(closeQty)
                    .multiply(new BigDecimal(currentPrice))
                    .multiply(TAKER_FEE_RATE)
                    .setScale(4, RoundingMode.HALF_UP);
            history.setFee(exitFee);

            CreateOrderRequest closeRequest = CreateOrderRequest.builder()
                    .category(Category.LINEAR.getCategory())
                    .symbol(session.getSymbol())
                    .side(closeSide)
                    .orderType("Market")
                    .qty(closeQty)
                    .reduceOnly(true) // 포지션 청산 전용 (새 포지션 열림 방지)
                    .build();

            // 주문 실행 (실패 시 TradeService에서 history에 실패 이력 저장 후 예외)
            tradeFacade.placeOrder(closeRequest, session.getUserId(), history, session.getTradeMode());

            // 주문 성공: 이력 저장 (모드에 따라 테이블 분기)
            history.setExecutedPrice(new BigDecimal(currentPrice));
            history.setOrderResult(OrderResult.SUCCESS);
            saveTradeHistory(history, session.getTradeMode());

            // 투자 히스토리 저장 (마진 + 패턴 정보 기준으로 손익 및 예상가 계산)
            if (state.getEntryMargin() == null) {
                // 방어 코드: 정상 흐름에서는 발생하지 않으나, 예외 상황 대비
                log.warn("투자 히스토리 저장 건너뜀: entryMargin null, queueId={}", queue.getId());
                session.addLog(now() + " [경고] 투자 히스토리를 저장할 수 없습니다 (진입 금액 정보 없음)");
            } else {
                // 진입 수수료 재계산 (state에서 entryQty, entryPrice 참조)
                BigDecimal entryFee = new BigDecimal(state.getEntryQty())
                        .multiply(new BigDecimal(state.getEntryPrice()))
                        .multiply(TAKER_FEE_RATE)
                        .setScale(4, RoundingMode.HALF_UP);
                saveInvestmentHistory(session.getUserId(), state.getActiveStepId(),
                        session.getSymbol(), state.getDirection(),
                        state.getEntryPrice(), currentPrice, state.getEntryMargin(), pattern, entryFee, exitFee, session.getTradeMode());
            }

            BigDecimal closeValue = new BigDecimal(closeQty).multiply(new BigDecimal(currentPrice))
                    .setScale(2, RoundingMode.HALF_UP);
            session.addLog(now() + " [매도 성공] " + closeSide + " "
                    + closeValue.stripTrailingZeros().toPlainString() + "$ "
                    + " (큐 #" + queue.getId() + ", " + state.getCurrentStepLevel() + "단계)");

            // SRS 11, 12: 매도 성공 → 1단계부터 다시 수행
            // 단, 재진입 시 매도 성공한 포지션 방향 그대로 1단계 패턴을 선택한다
            PatternStep firstStep = findStepByLevel(queue, 1);
            if (firstStep == null) {
                deactivateQueue(queue, state, session, "1단계가 존재하지 않음");
                return;
            }
            Pattern nextPattern = selectPattern(firstStep, state.getDirection());
            if (nextPattern == null) {
                // 1단계에 현재 방향 패턴이 없으면 큐 비활성화
                deactivateQueue(queue, state, session,
                        "1단계에 " + state.getDirection() + " 패턴 없음");
                return;
            }
            state.setCurrentStepLevel(1);
            state.setActiveStepId(firstStep.getId());
            state.setActivePatternId(nextPattern.getId());

            // 블록 리셋 + 다음 사이클 준비 (첫 블록은 매도 직후의 동일 방향 신호로 자동 매칭 간주)
            state.setCurrentBlockOrder(2);
            state.setEntryPrice(null);
            state.setEntryQty(null);
            state.setEntryMargin(null);
            state.setTpPrice(null);
            state.setSlPrice(null);
            state.setBlockBaseTime(null);
            state.setBlockBasePrice(null);
            state.setPhase(TradePhase.BLOCK_MATCHING);

        } catch (Exception e) {
            // 주문 실패 (TradeService에서 이미 history 저장 완료) → 큐 비활성화
            session.addLog(now() + " [매도 실패] " + e.getMessage());
            deactivateQueue(queue, state, session, "매도 실패: " + e.getMessage());
        }
    }

    /**
     * 청산 처리: 기존 포지션을 시장가로 닫고, 다음 단계로 이동한다. (SRS 12)
     * 모든 단계 소진 시 큐 비활성화 (SRS 14)
     *
     * @param queue                현재 큐
     * @param state                큐 런타임 상태
     * @param session              세션
     * @param currentPattern       현재 활성 패턴 (청산 수량 계산용)
     * @param liquidationDirection 청산을 유발한 신호 방향
     * @param currentPrice         현재가
     */
    private void handleLiquidation(PatternQueue queue, QueueStateDTO state,
                                   AutoTradeSessionDTO session, Pattern currentPattern,
                                   Side liquidationDirection, String currentPrice) {
        session.addLog(now() + " [청산] " + liquidationDirection + " 발생"
                + " (큐 #" + queue.getId() + ", " + state.getCurrentStepLevel() + "단계)");

        // 기존 포지션 시장가 청산 (진입 방향의 반대)
        String closeSide = state.getDirection() == Side.LONG ? "Sell" : "Buy";

        try {
            // 진입 시 저장한 qty 사용 (현재가 재계산 시 포지션 일부만 청산되는 문제 방지)
            String closeQty = state.getEntryQty();
            if (closeQty == null || "0".equals(closeQty)) {
                // 진입 수량 정보 없음 → 스킵 횟수 제한 적용
                int skipCount = state.getCloseSkipCount() + 1;
                state.setCloseSkipCount(skipCount);
                session.addLog(now() + " [청산 스킵] 청산 수량 확인 불가 (" + skipCount + "/5): " + session.getSymbol());
                if (skipCount >= 5) {
                    log.warn(LogMessage.CLOSE_QTY_SKIP_DEACTIVATED.getMessage(), queue.getId(), "LIQUIDATION");
                    session.addLog(now() + " [청산 중단] 청산 수량을 5회 연속 확인할 수 없어 큐를 비활성화합니다."
                            + " 열린 포지션을 수동으로 확인해 주세요.");
                    deactivateQueue(queue, state, session, "청산 수량 오류 5회 초과");
                }
                return;
            }

            TradeHistory history = new TradeHistory();
            history.setQueueStepId(state.getActiveStepId());
            history.setUserId(session.getUserId());
            history.setSymbol(session.getSymbol());
            history.setSide(state.getDirection());
            history.setOrderType(TradeOrderType.LIQUIDATION.getTradeOrderType());
            history.setAmount(state.getEntryMargin()); // 마진(실제 투자금) 저장

            // 청산 수수료 계산: closeQty × 체결가 × taker 요율
            BigDecimal exitFee = new BigDecimal(closeQty)
                    .multiply(new BigDecimal(currentPrice))
                    .multiply(TAKER_FEE_RATE)
                    .setScale(4, RoundingMode.HALF_UP);
            history.setFee(exitFee);

            CreateOrderRequest closeRequest = CreateOrderRequest.builder()
                    .category(Category.LINEAR.getCategory())
                    .symbol(session.getSymbol())
                    .side(closeSide)
                    .orderType("Market")
                    .qty(closeQty)
                    .reduceOnly(true) // 포지션 청산 전용
                    .build();

            tradeFacade.placeOrder(closeRequest, session.getUserId(), history, session.getTradeMode());

            // 청산 주문 성공: 이력 저장 (모드에 따라 테이블 분기)
            history.setExecutedPrice(new BigDecimal(currentPrice));
            history.setOrderResult(OrderResult.SUCCESS);
            saveTradeHistory(history, session.getTradeMode());

            // 투자 히스토리 저장 (마진 + 패턴 정보 기준으로 손익 및 예상가 계산)
            if (state.getEntryMargin() == null) {
                // 방어 코드: 정상 흐름에서는 발생하지 않으나, 예외 상황 대비
                log.warn("투자 히스토리 저장 건너뜀: entryMargin null, queueId={}", queue.getId());
                session.addLog(now() + " [경고] 투자 히스토리를 저장할 수 없습니다 (진입 금액 정보 없음)");
            } else {
                // 진입 수수료 재계산 (state에서 entryQty, entryPrice 참조)
                BigDecimal entryFee = new BigDecimal(state.getEntryQty())
                        .multiply(new BigDecimal(state.getEntryPrice()))
                        .multiply(TAKER_FEE_RATE)
                        .setScale(4, RoundingMode.HALF_UP);
                saveInvestmentHistory(session.getUserId(), state.getActiveStepId(),
                        session.getSymbol(), state.getDirection(),
                        state.getEntryPrice(), currentPrice, state.getEntryMargin(), currentPattern, entryFee, exitFee, session.getTradeMode());
            }
            state.setEntryMargin(null); // 포지션 리셋 시 초기화

            BigDecimal closeValue = new BigDecimal(closeQty).multiply(new BigDecimal(currentPrice))
                    .setScale(2, RoundingMode.HALF_UP);
            session.addLog(now() + " [청산 완료] " + closeSide + " "
                    + closeValue.stripTrailingZeros().toPlainString() + "$ "
                    + " (큐 #" + queue.getId() + ", " + state.getCurrentStepLevel() + "단계)");

        } catch (Exception e) {
            // 청산 주문 실패 → 큐 비활성화
            session.addLog(now() + " [청산 실패] " + e.getMessage());
            deactivateQueue(queue, state, session, "청산 주문 실패: " + e.getMessage());
            return;
        }

        // 다음 단계로 이동
        int nextStepLevel = state.getCurrentStepLevel() + 1;
        PatternStep nextStep = findStepByLevel(queue, nextStepLevel);

        if (nextStep == null) {
            // 모든 단계 소진 → 큐 비활성화 (SRS 14)
            deactivateQueue(queue, state, session, "모든 단계 소진");
            return;
        }

        // 청산 방향의 패턴 선택
        Pattern nextPattern = selectPattern(nextStep, liquidationDirection);
        if (nextPattern == null) {
            deactivateQueue(queue, state, session,
                    nextStepLevel + "단계에 " + liquidationDirection + " 패턴 없음");
            return;
        }

        // 상태 갱신: 다음 단계 + 새 패턴 + BLOCK_MATCHING (포지션 없이 블록 매칭 재시작)
        state.setCurrentStepLevel(nextStepLevel);
        state.setActiveStepId(nextStep.getId());
        state.setActivePatternId(nextPattern.getId());
        state.setDirection(liquidationDirection);
        // 첫 블록은 직전 청산 신호(liquidationDirection)로 자동 매칭 → 두 번째 블록부터 매칭
        state.setCurrentBlockOrder(2);
        state.setEntryPrice(null);
        state.setEntryQty(null);
        state.setTpPrice(null);
        state.setSlPrice(null);
        state.setBlockBaseTime(null);
        state.setBlockBasePrice(null);
        state.setPhase(TradePhase.BLOCK_MATCHING);

        session.addLog(now() + " [다음 단계] " + nextStepLevel + "단계 "
                + liquidationDirection + " 패턴 선택 (큐 #" + queue.getId() + ")");
    }

    // ─────────────────────────────────────────────
    // 유틸리티
    // ─────────────────────────────────────────────

    /**
     * 자동매매 세션 상태를 STOMP로 브라우저에 push한다.
     * 브라우저에서 /topic/autotrade.status.{symbol}을 구독하면 실시간으로 상태를 수신한다.
     *
     * <p>push되는 데이터는 기존 REST 폴링(/api/autotrade/status)과 동일한
     * {@link AutoTradeStatusResponse} 형태이므로, 프론트에서 동일한 처리 로직을 사용할 수 있다.</p>
     *
     * @param session 상태를 push할 자동매매 세션
     */
    private void pushAutoTradeStatus(AutoTradeSessionDTO session) {
        try {
            AutoTradeStatusResponse status = getStatusResponse(
                    session.getUserId(), session.getSymbol(), session.getTradeMode());
            // /topic/autotrade.status.{symbol} 토픽으로 전송
            // 해당 심볼의 차트 페이지를 보고 있는 브라우저만 이 토픽을 구독한다
            messagingTemplate.convertAndSend(
                    "/topic/autotrade.status." + session.getSymbol(), status);
        } catch (Exception e) {
            log.warn("STOMP 상태 push 실패: {}", e.getMessage());
        }
    }

    /**
     * 거래 히스토리를 모드에 따라 적절한 테이블에 저장한다.
     *
     * @param history   거래 히스토리
     * @param tradeMode 거래 모드 (MAIN: trade_history, SIM: sim_trade_history)
     */
    private void saveTradeHistory(TradeHistory history, TradeMode tradeMode) {
        if (tradeMode == TradeMode.SIM) {
            // SimTradeHistory로 변환하여 sim_trade_history 테이블에 저장
            SimTradeHistory simHistory = new SimTradeHistory();
            simHistory.setQueueStepId(history.getQueueStepId());
            simHistory.setUserId(history.getUserId());
            simHistory.setSymbol(history.getSymbol());
            simHistory.setSide(history.getSide());
            simHistory.setAmount(history.getAmount());
            simHistory.setExecutedPrice(history.getExecutedPrice());
            simHistory.setOrderType(history.getOrderType());
            simHistory.setOrderResult(history.getOrderResult());
            simHistory.setFee(history.getFee());
            simHistory.setErrorMessage(history.getErrorMessage());
            simTradeHistoryRepository.save(simHistory);
        } else {
            tradeHistoryRepository.save(history);
        }
    }

    /**
     * 투자 히스토리를 저장한다. (포지션 진입 → 매도/청산 사이클 완료 기록)
     * 손익금 계산: LONG  → (exitPrice - entryPrice) / entryPrice × margin × leverage
     *             SHORT → (entryPrice - exitPrice) / entryPrice × margin × leverage
     *
     * @param userId        사용자 ID
     * @param patternStepId 패턴 단계 ID
     * @param symbol        코인 심볼
     * @param side          투자 방향
     * @param entryPrice    진입가 문자열
     * @param exitPrice     청산가 문자열
     * @param margin        마진 (실제 투자금, 레버리지 미포함)
     * @param pattern       패턴 (레버리지, 익절/손절 비율 참조)
     * @param entryFee      진입 수수료 (DB 순손익 계산용 — 진입 시 SIM 지갑에서 이미 차감됨)
     * @param exitFee       청산 수수료 (DB 순손익 계산 + SIM 지갑 반영용)
     * @param tradeMode     거래 모드 (MAIN: investment_history, SIM: sim_investment_history)
     */
    private void saveInvestmentHistory(Long userId, Long patternStepId, String symbol,
                                       Side side, String entryPrice, String exitPrice,
                                       BigDecimal margin, Pattern pattern, BigDecimal entryFee, BigDecimal exitFee, TradeMode tradeMode) {
        BigDecimal entry = new BigDecimal(entryPrice);
        BigDecimal exit = new BigDecimal(exitPrice);
        int leverage = pattern.getLeverage();
        BigDecimal leverageBd = BigDecimal.valueOf(leverage);

        // 총손익 계산: 마진 × 레버리지 기준 (레버리지 반영)
        BigDecimal grossProfitLoss;
        if (side == Side.LONG) {
            // LONG: (청산가 - 진입가) / 진입가 × 마진 × 레버리지
            grossProfitLoss = exit.subtract(entry)
                    .divide(entry, 6, RoundingMode.HALF_UP)
                    .multiply(margin).multiply(leverageBd)
                    .setScale(4, RoundingMode.HALF_UP);
        } else {
            // SHORT: (진입가 - 청산가) / 진입가 × 마진 × 레버리지
            grossProfitLoss = entry.subtract(exit)
                    .divide(entry, 6, RoundingMode.HALF_UP)
                    .multiply(margin).multiply(leverageBd)
                    .setScale(4, RoundingMode.HALF_UP);
        }
        // 순손익 = 총손익 - (진입 수수료 + 청산 수수료) — DB 기록용
        BigDecimal totalFee = entryFee.add(exitFee);
        BigDecimal profitLoss = grossProfitLoss.subtract(totalFee);

        // 익절/손절 가격 계산 (pattern.takeProfitRate / stopLossRate 우선, 미설정 시 100/leverage 기본값)
        BigDecimal defaultThreshold = BigDecimal.valueOf(100.0 / leverage);
        BigDecimal tpRate = pattern.getTakeProfitRate() != null ? pattern.getTakeProfitRate() : defaultThreshold;
        BigDecimal slRate = pattern.getStopLossRate()   != null ? pattern.getStopLossRate()   : defaultThreshold;
        BigDecimal hundred = BigDecimal.valueOf(100);
        BigDecimal tpPrice;
        BigDecimal slPrice;
        if (side == Side.LONG) {
            tpPrice = entry.multiply(BigDecimal.ONE.add(tpRate.divide(hundred, 6, RoundingMode.HALF_UP)));
            slPrice = entry.multiply(BigDecimal.ONE.subtract(slRate.divide(hundred, 6, RoundingMode.HALF_UP)));
        } else {
            tpPrice = entry.multiply(BigDecimal.ONE.subtract(tpRate.divide(hundred, 6, RoundingMode.HALF_UP)));
            slPrice = entry.multiply(BigDecimal.ONE.add(slRate.divide(hundred, 6, RoundingMode.HALF_UP)));
        }

        if (tradeMode == TradeMode.SIM) {
            // SimInvestmentHistory로 sim_investment_history 테이블에 저장
            SimInvestmentHistory simHistory = new SimInvestmentHistory();
            simHistory.setUserId(userId);
            simHistory.setPatternStepId(patternStepId);
            simHistory.setSymbol(symbol);
            simHistory.setSide(side);
            simHistory.setEntryPrice(entry);
            simHistory.setExitPrice(exit);
            simHistory.setAmount(margin);
            simHistory.setLeverage(leverage);
            simHistory.setTpPrice(tpPrice);
            simHistory.setSlPrice(slPrice);
            simHistory.setProfitLoss(profitLoss);
            simInvestmentHistoryRepository.save(simHistory);

            // 모의투자 가상 지갑에 손익 반영
            // 진입 수수료는 진입 시 placeOrder에서 이미 차감됐으므로 청산 수수료만 반영
            BigDecimal walletProfitLoss = grossProfitLoss.subtract(exitFee);
            tradeFacade.applyProfitLoss(userId, margin, walletProfitLoss, tradeMode);
        } else {
            InvestmentHistory investmentHistory = new InvestmentHistory();
            investmentHistory.setUserId(userId);
            investmentHistory.setPatternStepId(patternStepId);
            investmentHistory.setSymbol(symbol);
            investmentHistory.setSide(side);
            investmentHistory.setEntryPrice(entry);
            investmentHistory.setExitPrice(exit);
            investmentHistory.setAmount(margin);
            investmentHistory.setLeverage(leverage);
            investmentHistory.setTpPrice(tpPrice);
            investmentHistory.setSlPrice(slPrice);
            investmentHistory.setProfitLoss(profitLoss);
            investmentHistoryRepository.save(investmentHistory);
        }

        log.info(LogMessage.INVESTMENT_HISTORY_SAVED.getMessage(), symbol, side, profitLoss, tradeMode);
    }

    /**
     * 단계 내에서 주어진 방향의 첫 번째 조건 블록이 일치하는 패턴을 선택한다.
     *
     * @param step      대상 단계
     * @param direction 매매 방향
     * @return 일치하는 패턴, 없으면 null
     */
    Pattern selectPattern(PatternStep step, Side direction) {
        for (Pattern pattern : step.getPatterns()) {
            // 첫 번째 조건 블록 (isLeaf=false, blockOrder 최솟값)
            PatternBlock firstCondBlock = pattern.getBlocks().stream()
                    .filter(b -> !b.isLeaf())
                    .min(Comparator.comparingInt(PatternBlock::getBlockOrder))
                    .orElse(null);

            if (firstCondBlock != null && firstCondBlock.getSide() == direction) {
                return pattern;
            }
        }
        return null;
    }

    /**
     * 큐를 비활성화하고 세션에서 제거한다.
     */
    private void deactivateQueue(PatternQueue queue, QueueStateDTO state,
                                 AutoTradeSessionDTO session, String reason) {
        queue.setActive(false);
        queue.setCurrentStepId(null);
        queue.setCurrentPatternId(null);
        queue.setCurrentBlockOrder(null);
        patternQueueRepository.save(queue);

        // 세션에서 큐 제거
        session.getQueues().removeIf(q -> q.getId().equals(queue.getId()));
        session.getQueueStates().remove(queue.getId());

        session.addLog(now() + " [큐 비활성화] 큐 #" + queue.getId() + " - " + reason);
        log.info(LogMessage.QUEUE_DEACTIVATED.getMessage(), queue.getId(), reason);

        // 모든 큐가 제거되면 세션 정리
        if (session.getQueues().isEmpty()) {
            activeSessions.remove(sessionKey(session.getUserId(), session.getSymbol(), session.getTradeMode()));
            syncWebSocketSubscriptions();
        }
    }

    /**
     * 큐에서 stepLevel에 해당하는 단계를 찾는다.
     */
    private PatternStep findStepByLevel(PatternQueue queue, int stepLevel) {
        return queue.getSteps().stream()
                .filter(s -> s.getStepLevel() == stepLevel)
                .findFirst()
                .orElse(null);
    }

    /**
     * 큐 전체에서 패턴 ID로 패턴을 찾는다.
     */
    private Pattern findPatternById(PatternQueue queue, Long patternId) {
        if (patternId == null) {
            return null;
        }
        return queue.getSteps().stream()
                .flatMap(s -> s.getPatterns().stream())
                .filter(p -> p.getId().equals(patternId))
                .findFirst()
                .orElse(null);
    }

    /**
     * 패턴에서 blockOrder에 해당하는 블록을 찾는다.
     */
    private PatternBlock findBlockByOrder(Pattern pattern, int blockOrder) {
        return pattern.getBlocks().stream()
                .filter(b -> b.getBlockOrder() == blockOrder)
                .findFirst()
                .orElse(null);
    }

    /**
     * 세션의 queueStates를 활성 큐 목록과 동기화한다.
     * 새 큐는 TRIGGER_WAIT로 초기화, 제거된 큐의 상태는 삭제한다.
     */
    private void syncQueueStates(AutoTradeSessionDTO session, List<PatternQueue> activeQueues) {
        Set<Long> activeQueueIds = activeQueues.stream()
                .map(PatternQueue::getId)
                .collect(Collectors.toSet());

        // 새 큐 초기화
        for (PatternQueue queue : activeQueues) {
            session.getQueueStates().computeIfAbsent(
                    queue.getId(), QueueStateDTO::initial);
        }

        // 제거된 큐 정리
        session.getQueueStates().keySet().removeIf(id -> !activeQueueIds.contains(id));
    }

    /**
     * 활성 세션의 심볼 목록과 WebSocket 구독을 동기화한다.
     */
    private void syncWebSocketSubscriptions() {
        Set<String> activeSymbols = activeSessions.values().stream()
                .map(AutoTradeSessionDTO::getSymbol)
                .collect(Collectors.toSet());

        bybitWebSocketClient.syncSubscriptions(activeSymbols);
    }

    private String sessionKey(Long userId, String symbol, TradeMode tradeMode) {
        return userId + ":" + symbol + ":" + tradeMode.name();
    }

    private String now() {
        return LocalDateTime.now().format(TIME_FMT);
    }
}
