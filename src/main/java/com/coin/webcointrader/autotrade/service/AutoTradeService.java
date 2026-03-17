package com.coin.webcointrader.autotrade.service;

import com.coin.webcointrader.autotrade.dto.AutoTradeSessionDTO;
import com.coin.webcointrader.autotrade.dto.AutoTradeStatusResponse;
import com.coin.webcointrader.autotrade.dto.QueueStateDTO;
import com.coin.webcointrader.autotrade.dto.TradePhase;
import com.coin.webcointrader.autotrade.repository.PatternQueueRepository;
import com.coin.webcointrader.autotrade.repository.TradeHistoryRepository;
import com.coin.webcointrader.common.client.market.BybitWebSocketClient;
import com.coin.webcointrader.common.dto.request.CreateOrderRequest;
import com.coin.webcointrader.common.dto.request.SetLeverageRequest;
import com.coin.webcointrader.common.dto.request.SetTradingStopRequest;
import com.coin.webcointrader.common.dto.response.FindTickerResponse;
import com.coin.webcointrader.common.entity.*;
import com.coin.webcointrader.common.enums.Category;
import com.coin.webcointrader.common.enums.OrderResult;
import com.coin.webcointrader.market.service.MarketService;
import com.coin.webcointrader.trade.service.TradeService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 자동매매 엔진.
 * WebSocket 실시간 가격 수신으로 이벤트 드리븐 매매를 실행하며,
 * 1초 주기 스케줄러는 WebSocket 장애 대비 안전망(fallback)으로 유지한다.
 *
 * 알고리즘 페이즈:
 * 1. TRIGGER_WAIT  - 트리거 조건 대기 (N초 후 ±N% 변동 시 방향 결정)
 * 2. POSITION_OPEN - 레버리지 설정 → 시장가 주문 → TP/SL 설정
 * 3. BLOCK_MATCHING - 블록 순차 매칭 → 리프 도달 시 매도, 반대 방향 시 청산
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AutoTradeService {
    private final PatternQueueRepository patternQueueRepository;
    private final TradeHistoryRepository tradeHistoryRepository;
    private final TradeService tradeService;
    private final MarketService marketService;
    private final BybitWebSocketClient bybitWebSocketClient;

    // 활성 세션 관리 (키: "userId:symbol")
    private final ConcurrentHashMap<String, AutoTradeSessionDTO> activeSessions = new ConcurrentHashMap<>();

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * 애플리케이션 시작 시 WebSocket 가격 리스너를 등록한다.
     */
    @PostConstruct
    public void init() {
        marketService.addPriceListener(this::onPriceUpdate);
        log.info("자동매매 WebSocket 가격 리스너 등록 완료");
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
                    log.error("자동매매 WS 처리 오류: symbol={}, error={}", symbol, e.getMessage());
                    session.addLog(now() + " [오류] " + e.getMessage());
                }
            }
        }
    }

    /**
     * 패턴 활성화 상태 변경 시 자동매매 세션을 동기화한다.
     * 활성 큐가 1개 이상이면 세션을 생성/갱신하고, 0개이면 세션을 제거한다.
     * WebSocket 구독도 함께 동기화한다.
     *
     * @param userId 사용자 ID
     * @param symbol 코인 심볼
     */
    public void syncSession(Long userId, String symbol) {
        // isActive=true인 패턴 큐만 조회
        List<PatternQueue> activeQueues = patternQueueRepository
                .findByUserIdAndSymbolAndIsActiveOrderByCreatedAtAsc(userId, symbol, true);

        String key = sessionKey(userId, symbol);

        if (activeQueues.isEmpty()) {
            if (activeSessions.remove(key) != null) {
                log.info("자동매매 중지: userId={}, symbol={} (활성 큐 없음)", userId, symbol);
            }
        } else {
            AutoTradeSessionDTO existing = activeSessions.get(key);
            if (existing != null) {
                // 기존 세션 갱신: 큐 목록 업데이트 + 새 큐의 상태 초기화
                existing.setQueues(activeQueues);
                syncQueueStates(existing, activeQueues);
                existing.addLog(now() + " [갱신] 활성 큐 " + activeQueues.size() + "개로 갱신");
                log.info("자동매매 세션 갱신: userId={}, symbol={}, 큐 {}개", userId, symbol, activeQueues.size());
            } else {
                // 신규 세션 생성
                AutoTradeSessionDTO session = new AutoTradeSessionDTO();
                session.setUserId(userId);
                session.setSymbol(symbol);
                session.setQueues(activeQueues);
                // 각 큐에 대해 초기 상태 생성
                syncQueueStates(session, activeQueues);
                activeSessions.put(key, session);
                log.info("자동매매 시작: userId={}, symbol={}, 큐 {}개", userId, symbol, activeQueues.size());
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
    public boolean isActive(Long userId, String symbol) {
        return activeSessions.containsKey(sessionKey(userId, symbol));
    }

    /**
     * 자동매매 세션 정보를 반환한다.
     *
     * @param userId 사용자 ID
     * @param symbol 코인 심볼
     * @return 활성 세션 DTO, 존재하지 않으면 null
     */
    public AutoTradeSessionDTO getSession(Long userId, String symbol) {
        return activeSessions.get(sessionKey(userId, symbol));
    }


    /**
     * 자동매매 상태 응답을 생성한다.
     * WebSocket 실시간 가격을 기반으로 트리거 경과시간/변동률을 계산한다.
     *
     * @param userId 사용자 ID
     * @param symbol 코인 심볼
     * @return 자동매매 상태 응답
     */
    public AutoTradeStatusResponse getStatusResponse(Long userId, String symbol) {
        if (!isActive(userId, symbol)) {
            return AutoTradeStatusResponse.builder()
                    .active(false)
                    .build();
        }

        AutoTradeSessionDTO session = getSession(userId, symbol);
        if (session.getQueues().isEmpty()) {
            return AutoTradeStatusResponse.builder().active(false).build();
        }

        // 첫 번째 활성 큐의 상태를 대표로 반환
        PatternQueue firstQueue = session.getQueues().get(0);
        QueueStateDTO state = session.getQueueStates().get(firstQueue.getId());

        // WebSocket 실시간 가격 조회
        long elapsedSeconds = 0;
        BigDecimal changeRate = null;
        BigDecimal amount = null;

        FindTickerResponse.TickerInfo wsTicker = marketService.getWsTicker(symbol);
        String currentPrice = wsTicker != null ? wsTicker.getLastPrice() : null;

        if (state != null && currentPrice != null) {
            // TRIGGER_WAIT: 경과시간 + 변동률 (기준가 대비)
            if (state.getPhase() == TradePhase.TRIGGER_WAIT && state.getBasePrice() != null) {
                elapsedSeconds = calculateTriggerTime(state.getBaseTime());
                changeRate = calculateTriggerRate(state.getBasePrice(), currentPrice);
            }
            // BLOCK_MATCHING: 변동률 (진입가 대비) + 투입 금액
            else if (state.getPhase() == TradePhase.BLOCK_MATCHING && state.getEntryPrice() != null) {
                changeRate = calculateTriggerRate(state.getEntryPrice(), currentPrice);
                // 활성 패턴에서 투입 금액 조회
                Pattern activePattern = findPatternById(firstQueue, state.getActivePatternId());
                if (activePattern != null) {
                    amount = activePattern.getAmount();
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
                .elapsedSeconds(elapsedSeconds)
                .changeRate(changeRate)
                .amount(amount)
                .build();
    }

    /**
     * 기준시간으로부터 경과된 시간을 반환한다.
     * @param baseTime 활성화된 기준 시간
     * @return 기준시간과 현재 시간의 차이 (long 타입)
     */
    public long calculateTriggerTime(LocalDateTime baseTime) {
         return Duration.between(baseTime, LocalDateTime.now()).getSeconds();
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
                log.error("자동매매 tick 오류: symbol={}, error={}", session.getSymbol(), e.getMessage());
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
        // 페이즈별 분기 처리
        switch (state.getPhase()) {
            case TRIGGER_WAIT -> processTriggerWait(queue, state, session, currentPrice);
            case POSITION_OPEN -> processPositionOpen(queue, state, session, currentPrice);
            case BLOCK_MATCHING -> processBlockMatching(queue, state, session, currentPrice);
        }
    }

    // ─────────────────────────────────────────────
    // Phase 1: 트리거 대기 (SRS 9)
    // ─────────────────────────────────────────────

    /**
     * 트리거 조건을 확인하여 매매 방향을 결정한다.
     * triggerSeconds초 후 triggerRate% 이상 변동 시 방향 결정,
     * 미달 시 기준가/시각을 리셋하여 재대기한다.
     *
     * @param queue        현재 큐
     * @param state        큐 런타임 상태
     * @param session      세션
     * @param currentPrice 현재가
     */
    void processTriggerWait(PatternQueue queue, QueueStateDTO state,
                            AutoTradeSessionDTO session, String currentPrice) {
        // 최초 호출 시 기준가/시각 설정
        if (state.getBasePrice() == null) {
            state.setBasePrice(currentPrice);
            state.setBaseTime(LocalDateTime.now());
            session.addLog(now() + " [트리거 대기] 큐 #" + queue.getId()
                    + " 기준가: " + currentPrice);
            return;
        }

        // 트리거 시간 경과 확인
        if (this.calculateTriggerTime(state.getBaseTime()) < queue.getTriggerSeconds()) {
            return; // 아직 대기 시간 미달
        }

        // 변동률 계산
        BigDecimal changeRate = this.calculateTriggerRate(state.getBasePrice(), currentPrice);
        BigDecimal triggerRate = queue.getTriggerRate();

        // 상승률 충족 → LONG
        if (changeRate.compareTo(triggerRate) >= 0) {
            state.setDirection(Side.LONG);
            transitionToPositionOpen(queue, state, session);
            session.addLog(now() + " [트리거 충족] 큐 #" + queue.getId()
                    + " 방향: LONG (변동률: " + changeRate.setScale(2, RoundingMode.HALF_UP) + "%)");
            return;
        }

        // 하락률 충족 → SHORT
        if (changeRate.compareTo(triggerRate.negate()) <= 0) {
            state.setDirection(Side.SHORT);
            transitionToPositionOpen(queue, state, session);
            session.addLog(now() + " [트리거 충족] 큐 #" + queue.getId()
                    + " 방향: SHORT (변동률: " + changeRate.setScale(2, RoundingMode.HALF_UP) + "%)");
            return;
        }

        // 변동률 미달 → 기준가/시각 리셋, 재대기
        state.setBasePrice(currentPrice);
        state.setBaseTime(LocalDateTime.now());
        session.addLog(now() + " [트리거 미달] 큐 #" + queue.getId()
                + " 변동률: " + changeRate.setScale(2, RoundingMode.HALF_UP) + "% → 재대기");
    }

    /**
     * 트리거 충족 후 POSITION_OPEN 페이즈로 전환한다.
     * 현재 단계에서 방향에 맞는 패턴을 선택한다.
     */
    private void transitionToPositionOpen(PatternQueue queue, QueueStateDTO state,
                                          AutoTradeSessionDTO session) {
        // 현재 단계 조회
        PatternStep currentStep = findStepByLevel(queue, state.getCurrentStepLevel());
        if (currentStep == null) {
            // 단계가 없으면 큐 비활성화
            deactivateQueue(queue, state, session, "단계 " + state.getCurrentStepLevel() + " 없음");
            return;
        }

        // 방향에 맞는 패턴 선택
        Pattern selectedPattern = selectPattern(currentStep, state.getDirection());
        if (selectedPattern == null) {
            // 패턴이 없으면 큐 비활성화
            deactivateQueue(queue, state, session,
                    state.getCurrentStepLevel() + "단계에 " + state.getDirection() + " 패턴 없음");
            return;
        }

        // 상태 갱신
        state.setActiveStepId(currentStep.getId());
        state.setActivePatternId(selectedPattern.getId());
        state.setCurrentBlockOrder(1);
        state.setPhase(TradePhase.POSITION_OPEN);
    }

    // ─────────────────────────────────────────────
    // Phase 2: 포지션 진입 (레버리지 + 주문 + TP/SL)
    // ─────────────────────────────────────────────

    /**
     * 레버리지를 설정하고 시장가 주문을 실행한다.
     * 주문 성공 시 진입가를 기록하고 BLOCK_MATCHING 페이즈로 전환한다.
     *
     * @param queue        현재 큐
     * @param state        큐 런타임 상태
     * @param session      세션
     * @param currentPrice 현재가 (진입가로 사용)
     */
    void processPositionOpen(PatternQueue queue, QueueStateDTO state,
                             AutoTradeSessionDTO session, String currentPrice) {
        Pattern pattern = findPatternById(queue, state.getActivePatternId());
        if (pattern == null) {
            session.addLog(now() + " [오류] 패턴을 찾을 수 없음: " + state.getActivePatternId());
            return;
        }

        String side = state.getDirection() == Side.LONG ? "Buy" : "Sell";
        String leverageStr = String.valueOf(pattern.getLeverage());

        // 레버리지 설정
        try {
            SetLeverageRequest leverageRequest = new SetLeverageRequest(
                    Category.LINEAR.getCategory(),
                    session.getSymbol(),
                    leverageStr,
                    leverageStr
            );
            tradeService.setLeverage(leverageRequest, session.getUserId());
        } catch (Exception e) {
            // 이미 동일 레버리지가 설정된 경우 Bybit이 에러를 반환할 수 있으므로 경고만 로그
            log.warn("레버리지 설정 실패 (계속 진행): {}", e.getMessage());
        }

        // 시장가 주문 실행
        CreateOrderRequest orderRequest = CreateOrderRequest.builder()
                .category(Category.LINEAR.getCategory())
                .symbol(session.getSymbol())
                .side(side)
                .orderType("Market")
                .qty(pattern.getAmount().stripTrailingZeros().toPlainString())
                .marketUnit("quoteCoin") // USDT 금액 기준 주문
                .build();

        // TradeHistory 준비
        TradeHistory history = new TradeHistory();
        history.setQueueStepId(state.getActiveStepId());
        history.setUserId(session.getUserId());
        history.setSymbol(session.getSymbol());
        history.setSide(state.getDirection());
        history.setAmount(pattern.getAmount());

        try {
            tradeService.placeOrder(orderRequest, session.getUserId());

            // 주문 성공
            history.setExecutedPrice(new BigDecimal(currentPrice));
            history.setOrderResult(OrderResult.SUCCESS);
            state.setEntryPrice(currentPrice);

            session.addLog(now() + " [진입] " + side + " "
                    + pattern.getAmount().stripTrailingZeros().toPlainString() + "$ "
                    + session.getSymbol() + " (큐 #" + queue.getId()
                    + ", " + state.getCurrentStepLevel() + "단계, x" + pattern.getLeverage() + ")");

            // TP/SL 설정 (패턴에 설정된 경우)
            setTradingStopIfNeeded(queue, state, session, pattern, currentPrice);

            // BLOCK_MATCHING 전환 (첫 조건 블록은 진입 방향으로 이미 소비)
            state.setCurrentBlockOrder(2);
            state.setPhase(TradePhase.BLOCK_MATCHING);

        } catch (Exception e) {
            // 주문 실패
            history.setExecutedPrice(BigDecimal.ZERO);
            history.setOrderResult(OrderResult.FAILED);
            history.setErrorMessage(e.getMessage());
            session.addLog(now() + " [진입 실패] " + e.getMessage());
            log.error("포지션 진입 실패: queue={}, error={}", queue.getId(), e.getMessage());
        }

        tradeHistoryRepository.save(history);
    }

    /**
     * 패턴에 손절/익절이 설정된 경우 Bybit Trading Stop을 설정한다.
     */
    private void setTradingStopIfNeeded(PatternQueue queue, QueueStateDTO state,
                                         AutoTradeSessionDTO session,
                                         Pattern pattern, String currentPrice) {
        // 손절/익절 모두 미설정이면 스킵
        if (pattern.getStopLossRate() == null && pattern.getTakeProfitRate() == null) {
            return;
        }

        BigDecimal entry = new BigDecimal(currentPrice);
        String tpPrice = "";
        String slPrice = "";

        // LONG 포지션: 익절 = 진입가 × (1 + rate/100), 손절 = 진입가 × (1 - rate/100)
        // SHORT 포지션: 익절 = 진입가 × (1 - rate/100), 손절 = 진입가 × (1 + rate/100)
        boolean isLong = state.getDirection() == Side.LONG;

        if (pattern.getTakeProfitRate() != null) {
            BigDecimal tpMultiplier = isLong
                    ? BigDecimal.ONE.add(pattern.getTakeProfitRate().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP))
                    : BigDecimal.ONE.subtract(pattern.getTakeProfitRate().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
            tpPrice = entry.multiply(tpMultiplier).setScale(2, RoundingMode.HALF_UP).toPlainString();
        }

        if (pattern.getStopLossRate() != null) {
            BigDecimal slMultiplier = isLong
                    ? BigDecimal.ONE.subtract(pattern.getStopLossRate().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP))
                    : BigDecimal.ONE.add(pattern.getStopLossRate().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
            slPrice = entry.multiply(slMultiplier).setScale(2, RoundingMode.HALF_UP).toPlainString();
        }

        try {
            // positionIdx: 0=단방향(One-Way), 1=Buy(Hedge), 2=Sell(Hedge) → Isolated는 0 사용
            SetTradingStopRequest tsRequest = new SetTradingStopRequest(
                    Category.LINEAR.getCategory(),
                    session.getSymbol(),
                    tpPrice,
                    slPrice,
                    0
            );
            tradeService.setTradingStop(tsRequest, session.getUserId());
            session.addLog(now() + " [TP/SL] TP:" + tpPrice + " SL:" + slPrice);
        } catch (Exception e) {
            log.warn("Trading Stop 설정 실패 (계속 진행): {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    // Phase 3: 블록 매칭 (SRS 11-14, 17)
    // ─────────────────────────────────────────────

    /**
     * 현재 블록과 가격 변동 신호를 매칭한다.
     * - 조건 블록 일치 → 다음 블록으로 진행
     * - 리프 블록 일치 → 매도 성공, 같은 단계 반복
     * - 반대 방향 → 청산, 다음 단계로 이동
     *
     * @param queue        현재 큐
     * @param state        큐 런타임 상태
     * @param session      세션
     * @param currentPrice 현재가
     */
    void processBlockMatching(PatternQueue queue, QueueStateDTO state,
                              AutoTradeSessionDTO session, String currentPrice) {
        Pattern pattern = findPatternById(queue, state.getActivePatternId());
        if (pattern == null) {
            return;
        }

        // 현재 블록 조회
        PatternBlock currentBlock = findBlockByOrder(pattern, state.getCurrentBlockOrder());
        if (currentBlock == null) {
            return;
        }

        // 신호 판별: 진입가 대비 변동률 기반
        Side signal = determineSignal(state, pattern, currentPrice);
        if (signal == null) {
            return; // 임계값 미달, 대기
        }

        // 신호와 현재 블록 비교
        if (signal == currentBlock.getSide()) {
            if (currentBlock.isLeaf()) {
                // 리프 블록 도달 → 매도 성공 (SRS 11)
                handleSellSuccess(queue, state, session, pattern, currentPrice);
            } else {
                // 조건 블록 일치 → 다음 블록으로 진행
                state.setCurrentBlockOrder(state.getCurrentBlockOrder() + 1);
                session.addLog(now() + " [블록 매칭] " + signal + " 일치 → 블록 "
                        + state.getCurrentBlockOrder() + " (큐 #" + queue.getId() + ")");
            }
        } else {
            // 반대 방향 → 청산 (SRS 12)
            handleLiquidation(queue, state, session, signal, currentPrice);
        }
    }

    /**
     * 진입가 대비 변동률로 매매 신호를 판별한다.
     * 수익 임계값 = takeProfitRate (설정 시) 또는 100/leverage (SRS 17)
     * 손실 임계값 = stopLossRate (설정 시) 또는 100/leverage (SRS 17)
     *
     * @param state        큐 상태 (진입가, 방향 정보)
     * @param pattern      현재 패턴 (레버리지, 손절/익절률)
     * @param currentPrice 현재가
     * @return LONG/SHORT 신호, 임계값 미달 시 null
     */
    Side determineSignal(QueueStateDTO state, Pattern pattern, String currentPrice) {
        BigDecimal entry = new BigDecimal(state.getEntryPrice());
        BigDecimal current = new BigDecimal(currentPrice);

        // 변동률 계산: (현재가 - 진입가) / 진입가 × 100
        BigDecimal changeRate = current.subtract(entry)
                .divide(entry, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        // 레버리지 기반 기본 임계값 (SRS 17)
        BigDecimal leverageThreshold = BigDecimal.valueOf(100).divide(
                BigDecimal.valueOf(pattern.getLeverage()), 6, RoundingMode.HALF_UP);

        // 수익 임계값: 익절률 설정 시 해당 값, 미설정 시 레버리지 기반
        BigDecimal profitThreshold = pattern.getTakeProfitRate() != null
                ? pattern.getTakeProfitRate() : leverageThreshold;

        // 손실 임계값: 손절률 설정 시 해당 값, 미설정 시 레버리지 기반
        BigDecimal lossThreshold = pattern.getStopLossRate() != null
                ? pattern.getStopLossRate() : leverageThreshold;

        // LONG 포지션: +profitThreshold → LONG(수익), -lossThreshold → SHORT(손실)
        // SHORT 포지션: -profitThreshold → SHORT(수익), +lossThreshold → LONG(손실)
        if (state.getDirection() == Side.LONG) {
            if (changeRate.compareTo(profitThreshold) >= 0) {
                return Side.LONG;
            }
            if (changeRate.compareTo(lossThreshold.negate()) <= 0) {
                return Side.SHORT;
            }
        } else {
            if (changeRate.compareTo(profitThreshold.negate()) <= 0) {
                return Side.SHORT;
            }
            if (changeRate.compareTo(lossThreshold) >= 0) {
                return Side.LONG;
            }
        }

        return null; // 임계값 미달
    }

    /**
     * 매도 성공 처리: 시장가 매도 → 같은 단계 반복 (SRS 11)
     */
    private void handleSellSuccess(PatternQueue queue, QueueStateDTO state,
                                   AutoTradeSessionDTO session,
                                   Pattern pattern, String currentPrice) {
        // 시장가 매도 실행 (진입 방향의 반대)
        String closeSide = state.getDirection() == Side.LONG ? "Sell" : "Buy";

        TradeHistory history = new TradeHistory();
        history.setQueueStepId(state.getActiveStepId());
        history.setUserId(session.getUserId());
        history.setSymbol(session.getSymbol());
        history.setSide(state.getDirection());
        history.setAmount(pattern.getAmount());

        try {
            CreateOrderRequest closeRequest = CreateOrderRequest.builder()
                    .category(Category.LINEAR.getCategory())
                    .symbol(session.getSymbol())
                    .side(closeSide)
                    .orderType("Market")
                    .qty(pattern.getAmount().stripTrailingZeros().toPlainString())
                    .marketUnit("quoteCoin") // USDT 금액 기준 주문
                    .build();

            tradeService.placeOrder(closeRequest, session.getUserId());

            history.setExecutedPrice(new BigDecimal(currentPrice));
            history.setOrderResult(OrderResult.SUCCESS);

            session.addLog(now() + " [매도 성공] " + closeSide + " "
                    + pattern.getAmount().stripTrailingZeros().toPlainString() + "$ "
                    + " (큐 #" + queue.getId() + ", " + state.getCurrentStepLevel() + "단계)");

            // 같은 단계 반복: 블록 리셋 → POSITION_OPEN (즉시 재진입)
            state.setCurrentBlockOrder(1);
            state.setEntryPrice(null);
            state.setPhase(TradePhase.POSITION_OPEN);

        } catch (Exception e) {
            history.setExecutedPrice(BigDecimal.ZERO);
            history.setOrderResult(OrderResult.FAILED);
            history.setErrorMessage(e.getMessage());
            session.addLog(now() + " [매도 실패] " + e.getMessage());
        }

        tradeHistoryRepository.save(history);
    }

    /**
     * 청산 처리: 다음 단계로 이동, 청산 방향의 패턴 선택 (SRS 12)
     * 모든 단계 소진 시 큐 비활성화 (SRS 14)
     */
    private void handleLiquidation(PatternQueue queue, QueueStateDTO state,
                                   AutoTradeSessionDTO session,
                                   Side liquidationDirection, String currentPrice) {
        session.addLog(now() + " [청산] " + liquidationDirection + " 발생"
                + " (큐 #" + queue.getId() + ", " + state.getCurrentStepLevel() + "단계)");

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

        // 상태 갱신: 다음 단계 + 새 패턴 + POSITION_OPEN
        state.setCurrentStepLevel(nextStepLevel);
        state.setActiveStepId(nextStep.getId());
        state.setActivePatternId(nextPattern.getId());
        state.setDirection(liquidationDirection);
        state.setCurrentBlockOrder(1);
        state.setEntryPrice(null);
        state.setPhase(TradePhase.POSITION_OPEN);

        session.addLog(now() + " [다음 단계] " + nextStepLevel + "단계 "
                + liquidationDirection + " 패턴 선택 (큐 #" + queue.getId() + ")");
    }

    // ─────────────────────────────────────────────
    // 유틸리티
    // ─────────────────────────────────────────────

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
        log.info("큐 비활성화: queue={}, reason={}", queue.getId(), reason);

        // 모든 큐가 제거되면 세션 정리
        if (session.getQueues().isEmpty()) {
            activeSessions.remove(sessionKey(session.getUserId(), session.getSymbol()));
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

    private String sessionKey(Long userId, String symbol) {
        return userId + ":" + symbol;
    }

    private String now() {
        return LocalDateTime.now().format(TIME_FMT);
    }
}
