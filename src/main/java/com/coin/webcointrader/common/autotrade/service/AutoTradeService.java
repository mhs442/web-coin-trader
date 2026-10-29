package com.coin.webcointrader.common.autotrade.service;

import com.coin.webcointrader.common.autotrade.entity.Pattern;
import com.coin.webcointrader.common.autotrade.entity.PatternQueue;
import com.coin.webcointrader.common.enums.Side;
import com.coin.webcointrader.common.autotrade.dto.AutoTradeAlertResponse;
import com.coin.webcointrader.common.autotrade.dto.AutoTradeSessionDTO;
import com.coin.webcointrader.common.autotrade.dto.AutoTradeStatusResponse;
import com.coin.webcointrader.common.autotrade.dto.QueueStateDTO;
import com.coin.webcointrader.common.autotrade.dto.TradePhase;
import com.coin.webcointrader.common.autotrade.repository.PatternQueueRepository;
import com.coin.webcointrader.common.trade.dto.InvestmentHistoryDTO;
import com.coin.webcointrader.common.trade.dto.TradeHistoryDTO;
import com.coin.webcointrader.common.ExchangeClientRegistry;
import com.coin.webcointrader.common.market.client.ExchangeMarketClient;
import com.coin.webcointrader.common.market.dto.KlineDTO;
import com.coin.webcointrader.common.market.dto.TickerDTO;
import com.coin.webcointrader.common.trade.dto.PositionCloseDTO;
import com.coin.webcointrader.common.trade.client.ExchangeTradeClient;
import com.coin.webcointrader.common.trade.dto.ClosedPnlDTO;
import com.coin.webcointrader.common.trade.dto.ClosedPnlQueryDTO;
import com.coin.webcointrader.common.trade.dto.ExecutionDTO;
import com.coin.webcointrader.common.trade.dto.OrderRequestDTO;
import com.coin.webcointrader.common.trade.dto.OrderResultDTO;
import com.coin.webcointrader.common.enums.PositionCloseType;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.autotrade.util.TradeCalculator;
import com.coin.webcointrader.common.enums.OrderSide;
import com.coin.webcointrader.common.enums.LogMessage;
import com.coin.webcointrader.common.enums.TradeMode;
import com.coin.webcointrader.common.enums.OrderCancelResult;
import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.enums.OrderResult;
import com.coin.webcointrader.common.enums.TradeOrderType;
import com.coin.webcointrader.common.market.service.MarketService;
import com.coin.webcointrader.common.trade.service.TradeFacade;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
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
    private final TradeFacade tradeFacade;
    private final MarketService marketService;
    private final ExchangeClientRegistry adapterRegistry;
    private final SimpMessagingTemplate messagingTemplate; // STOMP 메시지 전송 템플릿 (서버 → 브라우저 push용)
    private final ExecutorService closeRecordExecutor;     // 청산 후처리(체결 조회·이력 저장) 전용 실행기 (AsyncConfig)
    private final ExecutorService sessionLaneExecutor;     // 세션별 가격 처리 레인 실행기 (AsyncConfig, 고정 4개)

    // 활성 세션 관리 (키: "userId:exchangeType:symbol:tradeMode")
    private final ConcurrentHashMap<String, AutoTradeSessionDTO> activeSessions = new ConcurrentHashMap<>();

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    // 블록 매칭 고정 대기 시간 (초)
    private static final long BLOCK_WAIT_SECONDS = 60L;
    // 블록 기준 시각 유효 한도: 초과 시 봉 마감 신호 누락으로 간주하고 재대기
    private static final Duration BLOCK_BASE_STALE_LIMIT = Duration.ofMinutes(2);
    // 레인 실행 대기 경고 기준 (ms): 제출 후 실행까지 이 시간 이상 걸리면 warn 로그
    private static final long LANE_WAIT_WARN_MS = 3000L;
    // [사용자 정책 2026-10-23] 놓친 외부 청산 재조정 점검 주기 30초 — 사용자가 직접 정한 값이므로 변경 시 사용자 확인 필수
    // (짧을수록 감지가 빠르지만 보유 큐가 있는 사용자·심볼마다 포지션 조회 REST 호출이 늘어남)
    private static final long EXTERNAL_CLOSE_RECONCILE_INTERVAL_MS = 30_000L;
    // 진입 시각이 없는 상태(재시작 직후 등)의 청산 내역 검색 범위: 거래소 청산 손익 최대 조회 범위(7일, Bybit closed-pnl 기준)
    private static final long RECONCILE_FALLBACK_LOOKBACK_MS = 7L * 24 * 60 * 60 * 1000L;
    // [사용자 정책 2026-10-29] 청산 주문 접수 후 거래소 포지션 0 확인: 재확인 간격 1초, 5회 연속 미확인 시 큐 비활성화
    private static final long CLOSE_CONFIRM_INTERVAL_MS = 1000L;
    private static final int CLOSE_CONFIRM_MAX_FAILS = 5;
    // 큐 종료 청산 대기 한도 (초): 레인이 주문·확인 중이면 그만큼 기다림, 초과 시 실패 응답
    private static final long QUEUE_CLOSE_TIMEOUT_SEC = 30L;
    // 익절 지정가 등록 실패 이력의 오류 메시지 접두어
    private static final String TP_LIMIT_FAILED_PREFIX = "[익절 지정가 등록 실패] ";
    // 이력 저장 실패 fallback 이력의 오류 메시지 접두어 (실제 주문 실패와 구분)
    private static final String HISTORY_SAVE_FAILED_PREFIX = "[이력 저장 실패] 체결은 완료됨 — ";
    // 앱이 보낸 매도/청산 주문의 orderLinkId (q{큐ID}-S/L-{millis}) — 외부 청산 판단에서 제외
    // (진입 E는 거래소 TP/SL 발동 주문이 원주문 링크를 이어받을 가능성이 있어 제외 대상에 넣지 않음)
    private static final java.util.regex.Pattern OWN_CLOSE_LINK_ID = java.util.regex.Pattern.compile("^q\\d+-[SL]-\\d+$");

    /**
     * 애플리케이션 시작 시 WebSocket 가격/봉마감 리스너를 등록한다.
     * - 가격 push: leaf 즉시 진입, POSITION_HOLDING TP/SL 모니터링
     * - 봉 마감 push: 조건 블록 신호 판단 (1분봉 close vs open)
     */
    @PostConstruct
    public void init() {
        // 등록된 모든 거래소 시세 클라이언트에 가격/봉마감 리스너를 등록 (어느 거래소 push인지 함께 전달)
        for (ExchangeMarketClient market : adapterRegistry.markets()) {
            ExchangeType exchangeType = market.getExchangeType();
            market.addTickerListener(ticker -> onPriceUpdate(exchangeType, ticker.getSymbol(), ticker.getLastPrice()));
            market.addKlineListener(kline -> onKlineConfirmed(exchangeType, kline));
        }
        // 거래 클라이언트에 외부 청산(거래소 TP/SL·강제청산·수동 청산) 감지 리스너 등록
        // (private 스트림 미지원 거래소는 이벤트를 발행하지 않음)
        for (ExchangeTradeClient trade : adapterRegistry.trades()) {
            trade.addPositionCloseListener(this::onPositionClose);
        }
        log.info(LogMessage.AUTO_TRADE_LISTENER_REGISTERED.getMessage());
    }

    /**
     * 거래소 private 스트림에서 포지션 청산 체결이 수신되면 호출된다. (WebSocket 수신 스레드)
     * 앱이 보낸 매도/청산 주문(orderLinkId q{큐ID}-S/L-…)은 이미 처리했으므로 무시하고,
     * 나머지는 같은 사용자·거래소·심볼의 MAIN 세션 대기열에 넣어 레인에서 확인·전환하게 한다.
     *
     * @param event 청산 체결 이벤트
     */
    public void onPositionClose(PositionCloseDTO event) {
        if (activeSessions.isEmpty() || event == null) return;
        // 앱이 보낸 매도/청산 주문의 체결 → 이미 처리됨
        if (event.getOrderLinkId() != null && OWN_CLOSE_LINK_ID.matcher(event.getOrderLinkId()).matches()) return;

        for (AutoTradeSessionDTO session : activeSessions.values()) {
            // 실거래(MAIN) 세션 중 체결이 발생한 사용자·거래소·심볼만 대상
            if (session.getTradeMode() == TradeMode.MAIN
                    && event.getUserId().equals(session.getUserId())
                    && event.getExchangeType() == session.getExchangeType()
                    && event.getSymbol().equals(session.getSymbol())) {
                session.getPendingCloses().add(event);
                scheduleLane(session);
            }
        }
    }

    /**
     * WebSocket에서 가격 변동이 수신되면 호출된다.
     * 해당 거래소·심볼의 활성 세션에 최신 가격만 기록하고 세션 레인에 처리를 맡긴 뒤 즉시 반환한다.
     * (WS 스레드가 한 세션의 주문·체결 조회 대기로 막히지 않도록 분리)
     *
     * @param exchangeType 가격을 보낸 거래소
     * @param symbol       변동이 발생한 심볼 (예: "BTCUSDT")
     * @param newPrice     새로운 가격
     */
    public void onPriceUpdate(ExchangeType exchangeType, String symbol, String newPrice) {
        if (activeSessions.isEmpty()) {
            return;
        }

        // 해당 거래소·심볼의 활성 세션만 필터링
        for (AutoTradeSessionDTO session : activeSessions.values()) {
            if (session.getExchangeType() == exchangeType && session.getSymbol().equals(symbol)) {
                // 최신 가격만 기록 (처리 전 이전 가격은 덮어씀) — 실제 처리는 레인 스레드에서 수행
                session.getLatestPrice().set(newPrice);
                scheduleLane(session);
            }
        }
    }

    /**
     * 세션 레인 작업을 실행기에 제출한다.
     * 이미 제출된 작업이 있으면 그 작업이 최신 가격까지 처리하므로 추가 제출하지 않는다.
     * (세션당 동시에 1개 작업만 실행 → 같은 세션의 처리 순서 보장)
     *
     * @param session 처리할 자동매매 세션
     */
    private void scheduleLane(AutoTradeSessionDTO session) {
        // 이미 제출된 작업이 있으면 건너뜀
        if (!session.getLaneScheduled().compareAndSet(false, true)) {
            return;
        }
        try {
            // 제출 시각을 함께 넘겨 실행 대기 시간 측정
            long submittedAt = System.currentTimeMillis();
            sessionLaneExecutor.execute(() -> drainLane(session, submittedAt));
        } catch (RejectedExecutionException e) {
            // 애플리케이션 종료 중 제출 거부 → 플래그 원복
            session.getLaneScheduled().set(false);
            log.warn("[세션 레인] 작업 제출 거부: symbol={}, error={}", session.getSymbol(), e.getMessage());
        }
    }

    /**
     * 세션의 봉 마감 대기열과 최신 가격 슬롯이 빌 때까지 처리한다. (레인 스레드에서 실행)
     * 매 반복마다 쌓인 봉 마감을 순서대로 전부 처리한 뒤 최신 가격 1개를 처리한다.
     * (봉 마감으로 바뀐 블록 순서 기준으로 다음 가격을 판단하도록 봉 마감을 먼저 처리)
     * 처리 중 새 값이 들어오면 루프에서 이어서 처리하고, 처리할 때마다 STOMP로 세션 상태를 push한다.
     * 제출 후 실행까지 {@link #LANE_WAIT_WARN_MS} 이상 기다렸으면 경고 로그를 남긴다.
     *
     * @param session     처리할 자동매매 세션
     * @param submittedAt 레인 작업 제출 시각 (epoch ms)
     */
    private void drainLane(AutoTradeSessionDTO session, long submittedAt) {
        // 풀 스레드가 모두 주문·조회 중이라 실행이 늦어진 경우 → 지속되면 풀 크기 조정 검토
        long waitMs = System.currentTimeMillis() - submittedAt;
        if (waitMs >= LANE_WAIT_WARN_MS) {
            log.warn("[세션 레인] 실행 대기 지연: userId={}, symbol={}, waitMs={}",
                    session.getUserId(), session.getSymbol(), waitMs);
        }
        try {
            while (true) {
                // 대기 중 세션이 종료됐으면 남은 값을 비우고 종료 (비우지 않으면 재제출이 반복됨)
                if (!activeSessions.containsValue(session)) {
                    session.getPendingCloses().clear();
                    session.getPendingKlines().clear();
                    session.getLatestPrice().set(null);
                    // 사용자 명령은 응답을 기다리는 요청이 있으므로 실행해 완료시킴 (보유 큐가 없으면 즉시 끝남)
                    runPendingCommands(session);
                    return;
                }

                // 0) 외부 청산 먼저 처리 (포지션이 이미 닫혔으면 이후 가격으로 매도/청산 주문을 내지 않도록)
                PositionCloseDTO close;
                while ((close = session.getPendingCloses().poll()) != null) {
                    try {
                        processExternalClose(session, close);
                    } catch (Exception e) {
                        log.error(LogMessage.AUTO_TRADE_WS_ERROR.getMessage(), session.getSymbol(), e.getMessage());
                        session.addLog(now() + " [오류] " + e.getMessage());
                    }
                    pushAutoTradeStatus(session);
                }

                // 0-1) 사용자 명령 (큐 종료 청산 등) — 외부 청산 반영 후, 다음 가격 처리 전에 실행
                if (!session.getPendingCommands().isEmpty()) {
                    runPendingCommands(session);
                    pushAutoTradeStatus(session);
                }

                // 1) 쌓인 봉 마감을 순서대로 전부 처리 (봉당 1회 신호 → 유실 없이)
                KlineDTO kline;
                while ((kline = session.getPendingKlines().poll()) != null) {
                    try {
                        applyKlineSignal(session, kline, toSignal(kline));
                    } catch (Exception e) {
                        log.error(LogMessage.AUTO_TRADE_WS_ERROR.getMessage(), session.getSymbol(), e.getMessage());
                        session.addLog(now() + " [오류] " + e.getMessage());
                    }
                    pushAutoTradeStatus(session);
                }

                // 2) 최신 가격 1개 처리 (밀린 가격은 이미 덮어써짐), 없으면 종료
                String price = session.getLatestPrice().getAndSet(null);
                if (price == null) {
                    return;
                }
                try {
                    processSessionWithPrice(session, price);
                } catch (Exception e) {
                    log.error(LogMessage.AUTO_TRADE_WS_ERROR.getMessage(), session.getSymbol(), e.getMessage());
                    session.addLog(now() + " [오류] " + e.getMessage());
                }
                // STOMP: 가격 처리마다 해당 세션의 자동매매 상태를 브라우저에 push
                pushAutoTradeStatus(session);
            }
        } finally {
            session.getLaneScheduled().set(false);
            // 플래그 해제 직전에 들어온 값은 WS 스레드가 제출하지 못했으므로 재제출 (처리 누락 방지)
            if (session.getLatestPrice().get() != null || !session.getPendingKlines().isEmpty()
                    || !session.getPendingCloses().isEmpty() || !session.getPendingCommands().isEmpty()) {
                scheduleLane(session);
            }
        }
    }

    /** 세션 레인에 쌓인 사용자 명령을 순서대로 실행한다. (명령이 자체적으로 결과를 호출 측에 전달) */
    private void runPendingCommands(AutoTradeSessionDTO session) {
        Runnable command;
        while ((command = session.getPendingCommands().poll()) != null) {
            command.run();
        }
    }

    /**
     * 거래소가 1분봉 마감을 알려준 시점에 호출된다.
     * 해당 거래소·심볼의 활성 세션 중 BLOCK_MATCHING 상태이고 조건 블록을 관찰 중인 큐를 찾아
     * 봉의 close vs open 비교로 신호 방향을 판정한다.
     *
     * <p>큐의 blockBaseTime이 이 봉의 [start, end] 구간에 속할 때만 처리하여
     * 진입 직후 다른 봉의 마감 알림에 반응하지 않도록 한다.</p>
     *
     * @param exchangeType 봉 마감을 보낸 거래소
     * @param kline        마감된 1분봉 데이터 (심볼 포함)
     */
    public void onKlineConfirmed(ExchangeType exchangeType, KlineDTO kline) {
        if (activeSessions.isEmpty() || kline == null) return;
        // 구간(start~end)·시가·종가가 없으면 신호 판정 불가
        if (kline.getSymbol() == null || kline.getEndTime() <= 0
                || kline.getOpen() == null || kline.getClose() == null) return;

        for (AutoTradeSessionDTO session : activeSessions.values()) {
            if (session.getExchangeType() != exchangeType || !session.getSymbol().equals(kline.getSymbol())) continue;
            // 봉 마감은 대기열에 쌓고 레인에서 처리 (WS 스레드가 큐 락 대기로 막히지 않도록)
            session.getPendingKlines().add(kline);
            scheduleLane(session);
        }
    }

    /**
     * 마감된 봉의 신호 방향을 판정한다.
     *
     * @param kline 마감된 1분봉 (open/close 필수)
     * @return 양봉이면 LONG, 음봉이면 SHORT, 도지(close == open)이면 null
     */
    private Side toSignal(KlineDTO kline) {
        BigDecimal open = new BigDecimal(kline.getOpen());
        BigDecimal close = new BigDecimal(kline.getClose());
        int cmp = close.compareTo(open);
        if (cmp > 0) return Side.LONG;
        if (cmp < 0) return Side.SHORT;
        return null; // 도지 — 다음 봉 재대기
    }

    /**
     * 마감된 1분봉의 신호를 세션 내 각 큐에 적용한다.
     * 큐가 BLOCK_MATCHING 상태이고 현재 블록이 조건 블록(leaf 아님)이며
     * blockBaseTime이 이 봉 구간에 속할 때만 신호를 처리한다.
     * 세션 레인 스레드에서만 호출한다. (세션당 레인 1개 → 큐 상태 단일 스레드 접근, 락 불필요)
     */
    private void applyKlineSignal(AutoTradeSessionDTO session,
                                  KlineDTO kline, Side signal) {
        long startMs = kline.getStartTime();
        long endMs = kline.getEndTime();
        // 다음 판정 봉의 시작 시각 (end는 봉의 마지막 ms) — 서버 시계·레인 지연과 무관하게 다음 봉과 정확히 맞물림
        LocalDateTime nextCandleStart = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(endMs + 1), java.time.ZoneId.systemDefault());

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
            Side currentSide = findBlockSide(pattern, state.getCurrentBlockOrder());
            if (currentSide == null || isLeafOrder(pattern, state.getCurrentBlockOrder())) continue; // leaf는 push 기반 진입에서 처리

            if (signal == null) {
                // 도지: 기준 시각만 다음 봉으로 리셋 (다음 1분봉 마감 시 재판정)
                state.setBlockBaseTime(nextCandleStart);
                state.setBlockBasePrice(kline.getClose());
                session.addLog(now() + " [블록 매칭] 도지(close==open) → 다음 봉 재대기 (큐 #"
                        + queue.getId() + ")");
                continue;
            }

            if (signal == currentSide) {
                // 신호 일치 → 다음 블록으로 이동
                state.setCurrentBlockOrder(state.getCurrentBlockOrder() + 1);
                state.setBlockBaseTime(nextCandleStart);
                state.setBlockBasePrice(kline.getClose());
                session.addLog(now() + " [블록 매칭] " + signal + " 일치 → 블록 "
                        + state.getCurrentBlockOrder() + " (큐 #" + queue.getId() + ")");
            } else {
                // 신호 반대 → 같은 단계에서 신호 방향 시작 패턴 새로 선택 (단계 유지)
                // 단계당 패턴 2개 보장(LONG 시작+SHORT 시작)이라 selectPattern은 항상 매칭됨
                Pattern nextPattern = selectPattern(findPatternsByLevel(queue, state.getCurrentStepLevel()), signal);
                if (nextPattern == null) {
                    // 방어 코드: 단계당 양방향 패턴 검증을 통과한 큐에서는 발생하지 않음
                    log.warn("[패턴 전환 실패] 단계 {}에 {} 시작 패턴 없음, queueId={}",
                            state.getCurrentStepLevel(), signal, queue.getId());
                    deactivateQueue(queue, state, session,
                            state.getCurrentStepLevel() + "단계에 " + signal + " 시작 패턴 없음");
                    continue;
                }
                state.setActivePatternId(nextPattern.getId());
                state.setDirection(signal);
                // 첫 블록은 새 신호로 자동 매칭 간주 → 두 번째 블록부터 매칭 재시작
                state.setCurrentBlockOrder(2);
                // 전환된 패턴도 바로 다음 봉부터 판정 (null로 두면 레인 지연 시 다음 봉이 구간 밖으로 버려짐)
                state.setBlockBaseTime(nextCandleStart);
                state.setBlockBasePrice(kline.getClose());
                session.addLog(now() + " [패턴 전환] " + signal + " 신호 → 단계 "
                        + state.getCurrentStepLevel() + " " + signal + " 시작 패턴 (큐 #"
                        + queue.getId() + ")");
            }
        }
    }

    /**
     * 패턴 활성화 상태 변경 시 자동매매 세션을 동기화한다.
     * 활성 큐가 1개 이상이면 세션을 생성/갱신하고, 0개이면 세션을 제거한다.
     * WebSocket 구독도 함께 동기화한다.
     *
     * @param userId       사용자 ID
     * @param exchangeType 거래소 타입
     * @param symbol       코인 심볼
     * @param tradeMode    거래 모드 (MAIN/SIM)
     */
    public void syncSession(Long userId, ExchangeType exchangeType, String symbol, TradeMode tradeMode) {
        // isActive=true인 패턴 큐만 조회 (거래 모드 + 거래소별 분리)
        List<PatternQueue> activeQueues = patternQueueRepository
                .findByUserIdAndSymbolAndIsActiveAndTradeModeAndExchangeTypeOrderByCreatedAtAsc(
                        userId, symbol, true, tradeMode, exchangeType);

        String key = sessionKey(userId, exchangeType, symbol, tradeMode);

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
                session.setExchangeType(exchangeType);
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
     * @param userId       사용자 ID
     * @param exchangeType 거래소 타입
     * @param symbol       코인 심볼
     * @param tradeMode    거래 모드
     * @return 활성 세션이 존재하면 true
     */
    public boolean isActive(Long userId, ExchangeType exchangeType, String symbol, TradeMode tradeMode) {
        return activeSessions.containsKey(sessionKey(userId, exchangeType, symbol, tradeMode));
    }

    /**
     * 사용자가 해당 거래소·모드에서 실행 중인 자동매매 세션이 하나라도 있는지 확인한다. (심볼 무관)
     *
     * @param userId       사용자 ID
     * @param exchangeType 거래소 타입
     * @param tradeMode    거래 모드
     * @return 활성 세션이 하나라도 있으면 true
     */
    public boolean hasActiveSession(Long userId, ExchangeType exchangeType, TradeMode tradeMode) {
        return activeSessions.values().stream()
                .anyMatch(s -> userId.equals(s.getUserId())
                        && s.getExchangeType() == exchangeType
                        && s.getTradeMode() == tradeMode);
    }

    /**
     * 자동매매 세션 정보를 반환한다.
     *
     * @param userId       사용자 ID
     * @param exchangeType 거래소 타입
     * @param symbol       코인 심볼
     * @param tradeMode    거래 모드
     * @return 활성 세션 DTO, 존재하지 않으면 null
     */
    public AutoTradeSessionDTO getSession(Long userId, ExchangeType exchangeType, String symbol, TradeMode tradeMode) {
        return activeSessions.get(sessionKey(userId, exchangeType, symbol, tradeMode));
    }


    /**
     * 자동매매 상태 응답을 생성한다.
     * WebSocket 실시간 가격을 기반으로 트리거 경과시간/변동률을 계산한다.
     *
     * @param userId       사용자 ID
     * @param exchangeType 거래소 타입
     * @param symbol       코인 심볼
     * @param tradeMode    거래 모드
     * @return 자동매매 상태 응답
     */
    public AutoTradeStatusResponse getStatusResponse(Long userId, ExchangeType exchangeType,
                                                     String symbol, TradeMode tradeMode) {
        if (!isActive(userId, exchangeType, symbol, tradeMode)) {
            return AutoTradeStatusResponse.builder()
                    .active(false)
                    .build();
        }

        AutoTradeSessionDTO session = getSession(userId, exchangeType, symbol, tradeMode);
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

        // 포지션 보유 여부·익절 지정가 대기는 시세와 무관하게 상태로 판단
        List<Long> holdingQueueIds = session.getQueues().stream()
                .map(PatternQueue::getId)
                .filter(id -> {
                    QueueStateDTO s = session.getQueueStates().get(id);
                    return s != null && s.getPhase() == TradePhase.POSITION_HOLDING;
                })
                .toList();
        boolean tpOrderPending = state != null && state.getPhase() == TradePhase.POSITION_HOLDING
                && state.getTpOrderLinkId() != null;

        TickerDTO wsTicker = marketService.getTicker(exchangeType, symbol);
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
                .tpOrderPending(tpOrderPending)
                .holdingQueueIds(holdingQueueIds)
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
     * REST 조회는 스케줄러 스레드에서 하고, 처리는 WS 가격과 같은 세션 레인에 맡긴다.
     */
    @Scheduled(fixedRate = 1000)
    public void tick() {
        if (activeSessions.isEmpty()) {
            return;
        }
        // 거래소별로 판단 (한 거래소 WS 장애가 다른 거래소 세션에 영향 주지 않도록)
        Map<ExchangeType, List<AutoTradeSessionDTO>> sessionsByExchange = activeSessions.values().stream()
                .collect(Collectors.groupingBy(AutoTradeSessionDTO::getExchangeType));

        for (Map.Entry<ExchangeType, List<AutoTradeSessionDTO>> entry : sessionsByExchange.entrySet()) {
            ExchangeType exchangeType = entry.getKey();
            // WebSocket이 활성 상태이면 REST fallback 불필요 — 동시 processQueue 호출 방지
            if (marketService.isWsActive(exchangeType)) {
                continue;
            }

            List<TickerDTO> tickers = marketService.getTickers(exchangeType);
            if (tickers == null || tickers.isEmpty()) {
                continue;
            }

            for (AutoTradeSessionDTO session : entry.getValue()) {
                String currentPrice = findLastPrice(tickers, session.getSymbol());
                if (currentPrice == null) {
                    continue;
                }
                // WS 가격과 같은 슬롯에 기록 → 레인에서 처리 (같은 세션은 항상 레인 한 곳에서만 처리)
                session.getLatestPrice().set(currentPrice);
                scheduleLane(session);
            }
        }
    }

    /**
     * 놓친 외부 청산을 재조정한다. (스케줄러 스레드, 점검 주기는 사용자 정책 {@link #EXTERNAL_CLOSE_RECONCILE_INTERVAL_MS})
     * private WS가 끊긴 동안의 청산은 거래소가 다시 보내주지 않고, 키 문제로 private WS를 생략한 사용자도 있으므로
     * 포지션 보유 큐가 있는 MAIN 세션의 거래소 포지션을 주기적으로 확인한다.
     * - 포지션 남아 있음·조회 실패: 아무것도 하지 않음
     * - 포지션 0: 진입 이후 가장 최근 청산 건과 방식을 조회해 실시간 감지와 같은 경로({@link #onPositionClose})로 넘김
     *   (방식을 알 수 없으면 OTHER → 큐 비활성화, 레인에서 포지션 재확인 후 처리)
     */
    @Scheduled(fixedDelay = EXTERNAL_CLOSE_RECONCILE_INTERVAL_MS, initialDelay = EXTERNAL_CLOSE_RECONCILE_INTERVAL_MS)
    public void reconcileExternalCloses() {
        if (activeSessions.isEmpty()) return;

        for (AutoTradeSessionDTO session : new ArrayList<>(activeSessions.values())) {
            // 실거래 세션만 대상 (SIM은 거래소 포지션 없음)
            if (session.getTradeMode() != TradeMode.MAIN) continue;
            try {
                reconcileSession(session);
            } catch (Exception e) {
                log.warn("[외부 청산 재조정] 실패: userId={}, symbol={}, error={}",
                        session.getUserId(), session.getSymbol(), e.getMessage());
            }
        }
    }

    /**
     * 세션 1건의 놓친 외부 청산을 확인한다.
     *
     * @param session 대상 MAIN 세션
     */
    private void reconcileSession(AutoTradeSessionDTO session) {
        // 포지션 보유 큐의 진입 시각 수집 (보유 큐가 없으면 조회 생략)
        List<QueueStateDTO> holdingStates = session.getQueueStates().values().stream()
                .filter(st -> st.getPhase() == TradePhase.POSITION_HOLDING)
                .toList();
        if (holdingStates.isEmpty()) return;

        BigDecimal positionSize = tradeFacade.getPositionSize(session.getUserId(), session.getSymbol(), session.getExchangeType(), session.getTradeMode());
        // 조회 실패 또는 포지션 유지 중 → 다음 주기에 다시 확인
        if (positionSize == null || positionSize.signum() > 0) return;

        // 검색 시작점: 보유 큐 중 가장 이른 진입 시각 (진입 시각이 없는 큐가 있으면 최대 범위)
        long now = System.currentTimeMillis();
        long since = holdingStates.stream()
                .mapToLong(st -> st.getEntryTime() != null ? st.getEntryTime() : now - RECONCILE_FALLBACK_LOOKBACK_MS)
                .min()
                .orElse(now - RECONCILE_FALLBACK_LOOKBACK_MS);

        PositionCloseDTO close = tradeFacade.findLastClose(session.getUserId(), session.getSymbol(), since, session.getExchangeType(), session.getTradeMode());
        // 청산 내역 조회 실패 → 다음 주기에 재시도
        if (close == null) return;

        log.warn(LogMessage.EXTERNAL_CLOSE_RECONCILED.getMessage(),
                session.getUserId(), session.getSymbol(), close.getCloseType(), close.getOrderId());
        // 실시간 감지와 같은 경로로 처리 (앱 자체 매도/청산 건은 그 안에서 걸러짐)
        onPositionClose(close);
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
     * REST 폴링 티커 목록에서 심볼의 현재가를 찾는다. (fallback용)
     *
     * @param tickers REST 티커 목록
     * @param symbol  심볼 (예: "BTCUSDT")
     * @return 현재가, 목록에 없으면 null
     */
    private String findLastPrice(List<TickerDTO> tickers, String symbol) {
        for (TickerDTO ticker : tickers) {
            if (ticker.getSymbol().equals(symbol)) {
                return ticker.getLastPrice();
            }
        }
        return null;
    }

    /**
     * 개별 큐의 현재 페이즈에 따라 적절한 처리를 수행한다.
     * 세션 레인 스레드에서만 호출한다. (세션당 레인 1개 → 큐 상태 단일 스레드 접근, 락 불필요)
     */
    private void processQueue(PatternQueue queue, QueueStateDTO state,
                              AutoTradeSessionDTO session, String currentPrice) {
        // 페이즈별 분기 처리
        switch (state.getPhase()) {
            case TRIGGER_WAIT -> processTriggerWait(queue, state, session, currentPrice);
            case BLOCK_MATCHING -> processBlockMatching(queue, state, session, currentPrice);
            case POSITION_HOLDING -> processPositionHolding(queue, state, session, currentPrice);
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
        List<Pattern> currentStepPatterns = findPatternsByLevel(queue, state.getCurrentStepLevel());
        if (currentStepPatterns.isEmpty()) {
            deactivateQueue(queue, state, session, "단계 " + state.getCurrentStepLevel() + " 없음");
            return;
        }

        Pattern selectedPattern = selectPattern(currentStepPatterns, state.getDirection());
        if (selectedPattern == null) {
            deactivateQueue(queue, state, session,
                    state.getCurrentStepLevel() + "단계에 " + state.getDirection() + " 패턴 없음");
            return;
        }

        state.setActivePatternId(selectedPattern.getId());
        // 첫 블록은 트리거 신호로 자동 매칭된 것으로 간주 → 두 번째 블록부터 매칭 시작
        state.setCurrentBlockOrder(2);
        // 첫 블록 기준 시각/가격 초기화 (determineSignal에서 설정됨)
        state.setBlockBaseTime(null);
        state.setBlockBasePrice(null);
        state.setPhase(TradePhase.BLOCK_MATCHING);
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

        Side currentSide = findBlockSide(pattern, state.getCurrentBlockOrder());
        if (currentSide == null) return;

        // leaf 블록 도달 → 즉시 진입 (포지션 방향은 leaf의 side)
        if (isLeafOrder(pattern, state.getCurrentBlockOrder())) {
            log.info("leaf 블록 도달 → 진입: queueId={}, blockOrder={}, side={}, price={}",
                    queue.getId(), state.getCurrentBlockOrder(), currentSide, currentPrice);
            openPosition(queue, state, session, pattern, currentPrice);
            return;
        }
        // 가격 push마다 호출되므로 debug 레벨 (파일 로그 미기록)
        log.debug("조건 블록 봉 마감 대기: queueId={}, blockOrder={}, side={}",
                queue.getId(), state.getCurrentBlockOrder(), currentSide);

        // 봉 마감 신호 누락(WebSocket 단절 등)으로 기준 시각이 오래된 경우 → 현재 시각으로 재대기
        // 정상이라면 기준 시각 이후 최대 약 1분 내에 봉 마감 push가 와서 갱신된다
        if (state.getBlockBaseTime() != null
                && state.getBlockBaseTime().isBefore(LocalDateTime.now().minus(BLOCK_BASE_STALE_LIMIT))) {
            state.setBlockBaseTime(LocalDateTime.now());
            state.setBlockBasePrice(currentPrice);
            session.addLog(now() + " [블록 관찰] 봉 마감 신호 누락 → 재대기 - 블록 "
                    + state.getCurrentBlockOrder() + " (큐 #" + queue.getId() + ")");
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
        // leaf 블록 = 블록 목록의 마지막 원소
        Side entryDirection = findBlockSide(pattern, pattern.getBlocks().size());
        if (entryDirection == null) {
            session.addLog(now() + " [진입 실패] leaf 블록 없음: pattern=" + pattern.getId());
            deactivateQueue(queue, state, session, "leaf 블록 없음");
            return;
        }

        OrderSide side = entryDirection == Side.LONG ? OrderSide.BUY : OrderSide.SELL;
        String leverageStr = String.valueOf(pattern.getLeverage());

        // 1) 수량 계산 먼저 — 마진/레버리지 API 호출 전에 검증해서 무한 skip 시 API rate limit 회피
        BigDecimal notional = pattern.getAmount().multiply(BigDecimal.valueOf(pattern.getLeverage()));
        String qty = marketService.convertUsdtToQty(session.getExchangeType(), session.getSymbol(), notional, new BigDecimal(currentPrice));
        if (qty == null) {
            session.addLog(now() + " [진입 실패] qtyStep 조회 실패: " + session.getSymbol());
            deactivateQueue(queue, state, session, "수량 변환 실패: qtyStep 조회 실패");
            return;
        }
        if ("0".equals(qty)) {
            // 최소 주문 단위 미달: 누적 카운터 증가 후 한도 초과 시 큐 비활성화 (무한 skip 방지)
            int skipCount = state.getEntrySkipCount() + 1;
            state.setEntrySkipCount(skipCount);
            session.addLog(now() + " [진입 스킵] 최소 주문 단위 미달 (" + skipCount + "/5): "
                    + session.getSymbol() + " " + pattern.getAmount() + "USDT × x" + pattern.getLeverage()
                    + ", 현재가=" + currentPrice);
            if (skipCount >= 5) {
                log.error(LogMessage.ENTRY_SKIP_LIMIT_EXCEEDED.getMessage(),
                        queue.getId(), session.getSymbol(), notional, currentPrice);
                deactivateQueue(queue, state, session,
                        "최소 주문 단위 미달 5회 초과 — 투입 금액(amount × leverage = "
                                + notional.stripTrailingZeros().toPlainString()
                                + " USDT)이 부족합니다. 큐 수정에서 amount 또는 leverage를 늘려주세요.");
            }
            return;
        }
        // qty 정상 확보 → 진입 시도이므로 skip 카운터 리셋
        state.setEntrySkipCount(0);

        // 진입 거래 이력 (마진·레버리지 설정이 거부돼도 실패 이력으로 기록하므로 설정 전에 생성)
        TradeHistoryDTO history = new TradeHistoryDTO();
        history.setPatternId(state.getActivePatternId());
        history.setUserId(session.getUserId());
        history.setSymbol(session.getSymbol());
        history.setExchangeType(session.getExchangeType()); // 세션 거래소 기록
        history.setSide(entryDirection); // leaf side = 실제 포지션 방향
        history.setOrderType(TradeOrderType.ENTRY.getTradeOrderType());
        history.setAmount(pattern.getAmount()); // 마진 저장

        BigDecimal entryFee = TradeCalculator.calcFee(new BigDecimal(currentPrice), new BigDecimal(qty),
                takerFeeRate(session.getExchangeType()));
        history.setFee(entryFee);

        // 2) qty 정상 확보 후 마진 모드 Isolated 전환 (qty=0이면 여기 도달 X → API 호출 회피)
        try {
            tradeFacade.switchToIsolated(session.getUserId(), session.getSymbol(), history,
                    session.getExchangeType(), session.getTradeMode());
        } catch (Exception e) {
            session.addLog(now() + " [진입 실패] 마진 모드 Isolated 전환 실패: " + e.getMessage());
            log.error(LogMessage.MARGIN_MODE_SWITCH_FAILED.getMessage(), queue.getId(), e.getMessage());
            deactivateQueue(queue, state, session, "마진 모드 전환 실패: " + e.getMessage());
            return;
        }

        // 3) 레버리지 설정 (동일 값은 거래 클라이언트가 성공으로 처리하므로 여기 예외는 실제 실패)
        // [사용자 정책 2026-10-27] 실패 시 이전 레버리지로 진입하지 않도록 진입 중단 + 큐 비활성화
        try {
            tradeFacade.setLeverage(session.getUserId(), session.getSymbol(), leverageStr, history,
                    session.getExchangeType(), session.getTradeMode());
        } catch (Exception e) {
            session.addLog(now() + " [진입 실패] 레버리지 설정 실패: " + e.getMessage());
            log.error(LogMessage.LEVERAGE_SET_FAILED.getMessage(), queue.getId(), e.getMessage());
            deactivateQueue(queue, state, session, "레버리지 설정 실패: " + e.getMessage());
            return;
        }

        String orderLinkId = newOrderLinkId(queue, "E");
        OrderRequestDTO.OrderRequestDTOBuilder<?, ?> orderBuilder = OrderRequestDTO.builder()
                .symbol(session.getSymbol())
                .side(side)
                .qty(qty)
                .clientOrderId(orderLinkId); // 응답 불명확 시 결과 조회 + WS 체결 매칭용
        // 4) 서버 장애 대비 거래소 안전망 TP/SL 동봉 (앱 TP/SL보다 가격 0.5%p 바깥 — 정상 시 앱이 먼저 청산)
        attachExchangeTpSl(orderBuilder, entryDirection, pattern, currentPrice, queue, session);
        OrderRequestDTO orderRequest = orderBuilder.build();

        // 이중 진입 방지: 주문 전 진입가 선점
        state.setEntryPrice(currentPrice);
        // 진입 주문 전송 시각 (이 시각 이후의 청산 내역만 이 포지션의 청산으로 봄)
        long entrySentAt = System.currentTimeMillis();

        try {
            // WS 체결 대기는 주문 전에 등록 (체결 push가 주문 응답보다 먼저 올 수 있음, SIM은 무시)
            tradeFacade.expectExecution(session.getUserId(), orderLinkId, session.getExchangeType(), session.getTradeMode());
            OrderResultDTO orderResult = tradeFacade.placeOrder(orderRequest, session.getUserId(), history,
                    session.getExchangeType(), session.getTradeMode());

            // 실체결 데이터 조회 — WS 우선, 미수신 시 REST (MAIN 모드만, SIM은 null 반환)
            String orderId = orderResult != null ? orderResult.getOrderId() : null;
            ExecutionDTO exec = orderId != null
                    ? tradeFacade.getExecution(orderId, orderLinkId, session.getSymbol(), session.getUserId(),
                            session.getExchangeType(), session.getTradeMode())
                    : null;

            // 실체결가 / 실수수료 적용 (조회 실패 시 currentPrice / 추정 수수료 fallback)
            String actualEntryPrice = exec != null ? exec.getExecPrice() : currentPrice;
            BigDecimal actualEntryFee = exec != null ? new BigDecimal(exec.getExecFee()) : entryFee;

            history.setExecutedPrice(new BigDecimal(actualEntryPrice));
            history.setFee(actualEntryFee);
            history.setOrderId(orderId);

            // [사용자 정책 2026-10-29] 실체결 조회 실패(MAIN) 시 거래소 포지션으로 진입 체결을 확인한 뒤에만 보유 전환
            boolean confirmPending = false;
            if (exec == null && session.getTradeMode() != TradeMode.SIM) {
                BigDecimal positionSize = tradeFacade.getPositionSize(session.getUserId(), session.getSymbol(), session.getExchangeType(), session.getTradeMode());
                if (positionSize != null && positionSize.signum() == 0) {
                    // 포지션 0 → 진입 미체결
                    failUnfilledEntry(queue, state, session, history);
                    return;
                }
                // 조회 실패 → 확인 대기 (이력 저장 보류)
                confirmPending = positionSize == null;
            }

            if (confirmPending) {
                state.setPendingConfirmType(TradeOrderType.ENTRY);
                state.setPendingEntryHistory(history);
                state.setCloseConfirmFailCount(1);
                state.setCloseConfirmCheckedAt(System.currentTimeMillis());
                log.warn(LogMessage.ENTRY_NOT_CONFIRMED.getMessage(), queue.getId(), null, 1);
                session.addLog(now() + " [진입 확인 대기] 거래소 포지션 확인 전까지 익절·손절 판단을 보류합니다 (큐 #" + queue.getId() + ")");
            } else {
                history.setOrderResult(OrderResult.SUCCESS);
                // [사용자 정책 2026-10-29] 거래소 체결이 완료됐으므로 이력 저장 실패로 진입을 실패 처리하지 않는다 (fallback 이력만 남김)
                saveTradeHistoryOrFallback(history, orderId, session.getTradeMode(), queue.getId());
            }

            // 실체결가 / 실수수료를 state에 저장 (TP/SL 기준 및 InvestmentHistory 저장에 사용)
            state.setEntryPrice(actualEntryPrice);
            state.setEntryFee(actualEntryFee);
            state.setEntryQty(qty);
            state.setEntryMargin(pattern.getAmount());
            state.setEntryTime(entrySentAt);
            state.setCloseSkipCount(0);
            // 진입 후 state.direction을 leaf side로 갱신 — 이후 흐름(POSITION_HOLDING TP/SL 비교,
            // handleSellSuccess 1단계 재진입 등)이 실제 포지션 방향 기준으로 동작하도록 정합성 보장
            state.setDirection(entryDirection);

            // 익절/손절 가격 계산 — 실체결가 기준, rate는 투자금 기준 (%), 미설정 시 가격 8%
            BigDecimal entry = new BigDecimal(actualEntryPrice);
            int leverage = pattern.getLeverage();
            BigDecimal tpPrice = TradeCalculator.calcTpPrice(entryDirection, entry,
                    TradeCalculator.resolveRoiRate(pattern.getTakeProfitRate(), leverage), leverage);
            BigDecimal slPrice = TradeCalculator.calcSlPrice(entryDirection, entry,
                    TradeCalculator.resolveRoiRate(pattern.getStopLossRate(), leverage), leverage);
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

            // 진입이 확인된 경우에만 익절 지정가 등록 (확인 대기면 확인 완료 시 등록)
            if (!confirmPending) {
                placeTpLimitOrder(queue, state, session, currentPrice);
            }

        } catch (Exception e) {
            state.setEntryPrice(null);
            session.addLog(now() + " [진입 실패] " + e.getMessage());
            log.error(LogMessage.POSITION_ENTRY_FAILED.getMessage(), queue.getId(), e.getMessage());
            deactivateQueue(queue, state, session, "주문 실패: " + e.getMessage());
        } finally {
            // WS 체결 대기 정리 (주문 실패·orderId 없음 대비, 체결 조회에서 이미 제거됐으면 무시)
            tradeFacade.cancelExecution(orderLinkId, session.getExchangeType(), session.getTradeMode());
        }
    }

    /**
     * 주문별 고유 orderLinkId를 생성한다. 형식: q{큐ID}-{유형}-{epoch ms}
     * 거래소 제약(Bybit 기준 36자 이하, 영문·숫자·-·_)을 만족하며, 한 큐는 단일 스레드에서 순차 주문하므로 ms 단위로 고유하다.
     *
     * @param queue 주문을 실행하는 큐
     * @param type  주문 유형 (E: 진입, S: 매도, L: 청산)
     * @return orderLinkId (예: q12-E-1760950000000)
     */
    private String newOrderLinkId(PatternQueue queue, String type) {
        return "q" + queue.getId() + "-" + type + "-" + System.currentTimeMillis();
    }

    /**
     * 진입 주문에 거래소 안전망 TP/SL을 동봉한다.
     * 서버 장애·재시작으로 앱 모니터링이 끊겨도 거래소가 포지션을 정리하도록 하는 보조 장치이며,
     * 앱 TP/SL보다 가격 기준 0.5%p 바깥에 두어 정상 상황에서는 앱이 먼저 청산한다.
     * 발동 시 시장가로 포지션 전체를 1회 체결한다. (주문 방식 세부값은 거래소 구현체가 지정)
     *
     * <p>설정 생략 조건 (진입 자체는 계속):</p>
     * <ul>
     *   <li>tickSize 조회 실패 → TP/SL 모두 생략</li>
     *   <li>손절률이 강제청산 한도 초과(검증 도입 이전 패턴) → SL만 생략</li>
     *   <li>SHORT 익절가가 0 이하 → TP만 생략</li>
     * </ul>
     *
     * @param orderBuilder 진입 주문 빌더 (TP/SL 필드가 추가됨)
     * @param direction    진입 방향 (leaf side)
     * @param pattern      진입 패턴 (레버리지, 익절/손절률)
     * @param currentPrice 주문 시점 현재가 (거래소 TP/SL 기준가)
     * @param queue        대상 큐 (로그용)
     * @param session      사용자 세션 (경고 로그 기록)
     */
    private void attachExchangeTpSl(OrderRequestDTO.OrderRequestDTOBuilder<?, ?> orderBuilder, Side direction,
                                    Pattern pattern, String currentPrice,
                                    PatternQueue queue, AutoTradeSessionDTO session) {
        String tickSizeStr = marketService.getTickSize(session.getExchangeType(), session.getSymbol());
        if (tickSizeStr == null) {
            log.warn("거래소 안전 TP/SL 생략 (tickSize 조회 실패): queueId={}, symbol={}", queue.getId(), session.getSymbol());
            session.addLog(now() + " [경고] 가격 단위 조회 실패로 거래소 안전 손절/익절 없이 진입합니다 (큐 #" + queue.getId() + ")");
            return;
        }
        BigDecimal tickSize = new BigDecimal(tickSizeStr);
        BigDecimal basePrice = new BigDecimal(currentPrice);
        int leverage = pattern.getLeverage();

        BigDecimal tpRate = TradeCalculator.resolveRoiRate(pattern.getTakeProfitRate(), leverage);
        BigDecimal slRate = TradeCalculator.resolveRoiRate(pattern.getStopLossRate(), leverage);
        BigDecimal exchangeTp = TradeCalculator.calcExchangeTpPrice(direction, basePrice, tpRate, leverage, tickSize);

        // 손절률이 강제청산 한도를 넘으면 거래소가 SL을 거부할 수 있으므로 SL만 생략 (진입 주문 거부 방지)
        BigDecimal exchangeSl = null;
        if (TradeCalculator.isStopLossWithinLimit(slRate, leverage)) {
            exchangeSl = TradeCalculator.calcExchangeSlPrice(direction, basePrice, slRate, leverage, tickSize);
        } else {
            log.warn("거래소 안전 SL 생략 (강제청산 한도 초과): queueId={}, slRate={}, leverage={}",
                    queue.getId(), slRate, leverage);
            session.addLog(now() + " [경고] 손절률이 강제청산 한도("
                    + TradeCalculator.maxStopLossRate(leverage).stripTrailingZeros().toPlainString()
                    + "%)를 넘어 거래소 안전 손절 없이 진입합니다 (큐 #" + queue.getId() + ") — 큐 수정에서 손절률을 낮춰 주세요.");
        }

        if (exchangeTp != null) {
            orderBuilder.takeProfit(exchangeTp.stripTrailingZeros().toPlainString());
        }
        if (exchangeSl != null) {
            orderBuilder.stopLoss(exchangeSl.stripTrailingZeros().toPlainString());
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
        // 진입·청산 주문 접수 후 완료 확인 대기 중 → 신규 주문 없이 포지션만 재확인 (간격 제한)
        if (state.getPendingConfirmType() != null) {
            if (System.currentTimeMillis() - state.getCloseConfirmCheckedAt() >= CLOSE_CONFIRM_INTERVAL_MS) {
                checkPendingConfirm(queue, state, session);
            }
            return;
        }

        // 방어 코드: TP/SL 미세팅 (정상 흐름에서는 발생하지 않음)
        if (state.getTpPrice() == null || state.getSlPrice() == null) {
            log.warn("[POSITION_HOLDING] TP/SL 미설정 - queueId={}", queue.getId());
            return;
        }

        Pattern pattern = findPatternById(queue, state.getActivePatternId());
        if (pattern == null) return;

        BigDecimal current = new BigDecimal(currentPrice);
        boolean tpLimitPending = state.getTpOrderLinkId() != null;

        if (state.getDirection() == Side.LONG) {
            // LONG 익절: 가격 상승해 TP 도달 (익절 지정가가 걸려 있으면 거래소 체결 이벤트로 처리)
            if (current.compareTo(state.getTpPrice()) >= 0) {
                if (tpLimitPending) return;
                handleSellSuccess(queue, state, session, pattern, currentPrice);
            }
            // LONG 손절: 가격 하락해 SL 도달 → 반대(SHORT) 방향 신호로 다음 단계 이동
            else if (current.compareTo(state.getSlPrice()) <= 0) {
                if (tpLimitPending) handleStopLossWithTpLimit(queue, state, session, pattern, Side.SHORT, currentPrice);
                else handleLiquidation(queue, state, session, pattern, Side.SHORT, currentPrice);
            }
        } else {
            // SHORT 익절: 가격 하락해 TP 도달 (익절 지정가가 걸려 있으면 거래소 체결 이벤트로 처리)
            if (current.compareTo(state.getTpPrice()) <= 0) {
                if (tpLimitPending) return;
                handleSellSuccess(queue, state, session, pattern, currentPrice);
            }
            // SHORT 손절: 가격 상승해 SL 도달 → 반대(LONG) 방향 신호로 다음 단계 이동
            else if (current.compareTo(state.getSlPrice()) >= 0) {
                if (tpLimitPending) handleStopLossWithTpLimit(queue, state, session, pattern, Side.LONG, currentPrice);
                else handleLiquidation(queue, state, session, pattern, Side.LONG, currentPrice);
            }
        }
    }

    // [제거] handleNextStep: 조건 블록 반대 신호는 단계 이동이 아니라 같은 단계 내 패턴 전환으로 처리됨 (applyKlineSignal 참고).
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
        OrderSide closeSide = state.getDirection() == Side.LONG ? OrderSide.SELL : OrderSide.BUY;

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

            TradeHistoryDTO history = new TradeHistoryDTO();
            history.setPatternId(state.getActivePatternId());
            history.setUserId(session.getUserId());
            history.setSymbol(session.getSymbol());
            history.setExchangeType(session.getExchangeType()); // 세션 거래소 기록
            history.setSide(state.getDirection());
            history.setOrderType(TradeOrderType.SELL.getTradeOrderType());
            history.setAmount(state.getEntryMargin()); // 마진(실제 투자금) 저장

            // [사용자 정책 2026-10-29] SIM 익절은 실거래 익절 지정가와 같게: 체결가 = 지정가, 수수료 = maker
            //   (지정가는 진입 시 미리 계산, 없으면 익절가). MAIN은 기존대로 현재가·taker
            String closePrice = currentPrice;
            BigDecimal exitFeeRate = takerFeeRate(session.getExchangeType());
            if (session.getTradeMode() == TradeMode.SIM) {
                BigDecimal simTpPrice = state.getTpOrderPrice() != null ? state.getTpOrderPrice() : state.getTpPrice();
                closePrice = simTpPrice.toPlainString();
                exitFeeRate = adapterRegistry.trade(session.getExchangeType()).getMakerFeeRate();
            }

            // 추정 수수료: 주문 실패 시 TradeFacade가 이 값으로 이력을 저장하므로 fallback용으로 먼저 세팅
            BigDecimal estimatedExitFee = TradeCalculator.calcFee(
                    new BigDecimal(closePrice), new BigDecimal(closeQty), exitFeeRate);
            history.setFee(estimatedExitFee);

            String orderLinkId = newOrderLinkId(queue, "S");
            OrderRequestDTO closeRequest = OrderRequestDTO.builder()
                    .symbol(session.getSymbol())
                    .side(closeSide)
                    .qty(closeQty)
                    .reduceOnly(true) // 포지션 청산 전용 (새 포지션 열림 방지)
                    .clientOrderId(orderLinkId) // 응답 불명확 시 결과 조회용 + WS 체결 매칭 키
                    .build();

            // 주문 실행 (실패 시 TradeFacade에서 history에 실패 이력 저장 후 예외)
            OrderResultDTO orderResult = placeCloseOrder(closeRequest, session, history, orderLinkId);

            // 체결 조회·이력 저장은 후처리 실행기에 위임 (상태 리셋 전에 진입 정보 스냅샷)
            submitCloseRecord(CloseRecord.of(orderResult, orderLinkId, history, "매도", queue, state, session,
                    pattern, closeSide, closeQty, closePrice, estimatedExitFee), session);

            // 즉시 로그는 체결 기준가 추정 금액 (실체결가는 후처리 완료 시 [체결 확정] 로그로 표시)
            session.addLog(now() + " [매도 성공] " + closeSide + " "
                    + estimateCloseValue(closeQty, closePrice) + "$ (추정)"
                    + " (큐 #" + queue.getId() + ", " + state.getCurrentStepLevel() + "단계)");

            // 거래소 청산 완료 확인 후에만 1단계 리셋
            awaitCloseConfirmed(queue, state, session, TradeOrderType.SELL, null);

        } catch (Exception e) {
            // 주문 실패 (TradeFacade에서 이미 history 저장 완료) → 이미 닫힌 포지션인지 확인 후 처리
            session.addLog(now() + " [매도 실패] " + e.getMessage());
            handleCloseOrderFailure(queue, state, session, "매도 실패: " + e.getMessage());
        }
    }

    /**
     * 매도(익절) 완료 후 상태 전환: 1단계부터 다시 수행한다. (SRS 11, 12)
     * 재진입 시 매도 성공한 포지션 방향 그대로 1단계 패턴을 선택하며, 패턴이 없으면 큐를 비활성화한다.
     */
    private void resetToFirstStepAfterSell(PatternQueue queue, QueueStateDTO state, AutoTradeSessionDTO session) {
        List<Pattern> firstStepPatterns = findPatternsByLevel(queue, 1);
        if (firstStepPatterns.isEmpty()) {
            deactivateQueue(queue, state, session, "1단계가 존재하지 않음");
            return;
        }
        Pattern nextPattern = selectPattern(firstStepPatterns, state.getDirection());
        if (nextPattern == null) {
            // 1단계에 현재 방향 패턴이 없으면 큐 비활성화
            deactivateQueue(queue, state, session,
                    "1단계에 " + state.getDirection() + " 패턴 없음");
            return;
        }
        state.setCurrentStepLevel(1);
        state.setActivePatternId(nextPattern.getId());

        // 블록 리셋 + 다음 사이클 준비 (첫 블록은 매도 직후의 동일 방향 신호로 자동 매칭 간주)
        state.setCurrentBlockOrder(2);
        state.setEntryPrice(null);
        state.setEntryQty(null);
        state.setEntryMargin(null);
        state.setEntryFee(null);
        state.setEntryTime(null);
        state.setTpPrice(null);
        state.setSlPrice(null);
        state.setTpOrderLinkId(null);
        state.setTpOrderId(null);
        state.setTpOrderPrice(null);
        state.setTpCancelFailCount(0);
        state.setBlockBaseTime(null);
        state.setBlockBasePrice(null);
        state.setPhase(TradePhase.BLOCK_MATCHING);
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
        closeForStopLoss(queue, state, session, currentPattern, liquidationDirection, currentPrice, null, null, false);
    }

    /**
     * 손절 시장가 청산 본문. 부분 익절 후 손절(D1)이면 잔여 수량만 청산하고, 합산 손익 부호에 따라 전환 경로를 정한다.
     *
     * @param queue                현재 큐
     * @param state                큐 런타임 상태
     * @param session              세션
     * @param currentPattern       현재 활성 패턴
     * @param liquidationDirection 청산을 유발한 신호 방향 (손절 경로의 다음 단계 진입 방향)
     * @param currentPrice         현재가
     * @param closeQtyOverride     청산 수량 (null이면 진입 수량 전량)
     * @param partialTp            부분 익절 체결 정보 (없으면 null)
     * @param takeProfitPath       true면 청산 확인 후 익절 경로(1단계·같은 방향), false면 손절 경로(다음 단계·반대 방향)
     */
    private void closeForStopLoss(PatternQueue queue, QueueStateDTO state,
                                  AutoTradeSessionDTO session, Pattern currentPattern,
                                  Side liquidationDirection, String currentPrice,
                                  String closeQtyOverride, PartialTpFill partialTp, boolean takeProfitPath) {
        session.addLog(now() + " [청산] " + liquidationDirection + " 발생"
                + " (큐 #" + queue.getId() + ", " + state.getCurrentStepLevel() + "단계)");

        // 기존 포지션 시장가 청산 (진입 방향의 반대)
        OrderSide closeSide = state.getDirection() == Side.LONG ? OrderSide.SELL : OrderSide.BUY;

        try {
            // 진입 시 저장한 qty 사용 (현재가 재계산 시 포지션 일부만 청산되는 문제 방지), 부분 익절 후면 잔여 수량
            String closeQty = closeQtyOverride != null ? closeQtyOverride : state.getEntryQty();
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

            TradeHistoryDTO history = new TradeHistoryDTO();
            history.setPatternId(state.getActivePatternId());
            history.setUserId(session.getUserId());
            history.setSymbol(session.getSymbol());
            history.setExchangeType(session.getExchangeType()); // 세션 거래소 기록
            history.setSide(state.getDirection());
            history.setOrderType(TradeOrderType.LIQUIDATION.getTradeOrderType());
            // 마진(실제 투자금) 저장, 부분 익절 후면 잔여 수량 비율만큼
            history.setAmount(partialTp != null
                    ? proportionalMargin(state.getEntryMargin(), closeQty, state.getEntryQty())
                    : state.getEntryMargin());

            // 추정 수수료: 주문 실패 시 TradeFacade가 이 값으로 이력을 저장하므로 fallback용으로 먼저 세팅
            BigDecimal estimatedExitFee = TradeCalculator.calcFee(
                    new BigDecimal(currentPrice), new BigDecimal(closeQty), takerFeeRate(session.getExchangeType()));
            history.setFee(estimatedExitFee);

            String orderLinkId = newOrderLinkId(queue, "L");
            OrderRequestDTO closeRequest = OrderRequestDTO.builder()
                    .symbol(session.getSymbol())
                    .side(closeSide)
                    .qty(closeQty)
                    .reduceOnly(true) // 포지션 청산 전용
                    .clientOrderId(orderLinkId) // 응답 불명확 시 결과 조회용 + WS 체결 매칭 키
                    .build();

            OrderResultDTO orderResult = placeCloseOrder(closeRequest, session, history, orderLinkId);

            // 체결 조회·이력 저장은 후처리 실행기에 위임 (상태 이동 전에 진입 정보 스냅샷)
            submitCloseRecord(CloseRecord.of(orderResult, orderLinkId, history, "청산", queue, state, session,
                    currentPattern, closeSide, closeQty, currentPrice, estimatedExitFee).withPartialTp(partialTp), session);
            state.setEntryMargin(null); // 포지션 리셋 시 초기화

            // 즉시 로그는 현재가 기준 추정 금액 (실체결가는 후처리 완료 시 [체결 확정] 로그로 표시)
            session.addLog(now() + " [청산 완료] " + closeSide + " "
                    + estimateCloseValue(closeQty, currentPrice) + "$ (추정)"
                    + " (큐 #" + queue.getId() + ", " + state.getCurrentStepLevel() + "단계)");

        } catch (Exception e) {
            // 청산 주문 실패 → 이미 닫힌 포지션인지 확인 후 처리
            session.addLog(now() + " [청산 실패] " + e.getMessage());
            handleCloseOrderFailure(queue, state, session, "청산 주문 실패: " + e.getMessage());
            return;
        }

        // 거래소 청산 완료 확인 후에만 상태 전환 (부분 익절 합산 손익 ≥ 0이면 익절 경로)
        if (takeProfitPath) {
            awaitCloseConfirmed(queue, state, session, TradeOrderType.SELL, null);
        } else {
            awaitCloseConfirmed(queue, state, session, TradeOrderType.LIQUIDATION, liquidationDirection);
        }
    }

    /**
     * 익절 지정가가 걸린 상태의 손절 처리 (2-2).
     * 익절 지정가를 먼저 취소하고 거래소 잔여 포지션으로 익절 체결 수량을 판단한다.
     * - 취소 실패·포지션 조회 실패(④): 이번 틱 손절 보류, 1초 간격 재시도, 5회 연속이면 큐 비활성화
     * - 잔여 0: 익절 전량 체결 또는 거래소 청산 → 시장가 손절 생략, 거래소 청산 내역 기준으로 전환
     * - 체결 없음: 전량 시장가 손절 (기존과 동일)
     * - 부분 체결(D1): 잔여만 시장가 청산, 합산 추정 손익 ≥ 0이면 익절 경로 / < 0이면 손절 경로
     *
     * @param queue                현재 큐
     * @param state                큐 런타임 상태 (tpOrderLinkId 설정됨)
     * @param session              세션
     * @param pattern              현재 활성 패턴
     * @param liquidationDirection 손절 경로의 다음 단계 진입 방향
     * @param currentPrice         현재가
     */
    private void handleStopLossWithTpLimit(PatternQueue queue, QueueStateDTO state, AutoTradeSessionDTO session,
                                           Pattern pattern, Side liquidationDirection, String currentPrice) {
        // 재시도 중이면 1초 간격으로만 시도
        long nowMs = System.currentTimeMillis();
        if (state.getTpCancelFailCount() > 0 && nowMs - state.getCloseConfirmCheckedAt() < CLOSE_CONFIRM_INTERVAL_MS) return;
        state.setCloseConfirmCheckedAt(nowMs);

        // 1) 익절 지정가 취소 — NOT_FOUND(이미 전량 체결·취소)도 주문이 없다는 뜻이므로 진행
        OrderCancelResult cancelResult = tradeFacade.cancelOrder(session.getUserId(), session.getSymbol(),
                state.getTpOrderLinkId(), session.getExchangeType(), session.getTradeMode());
        if (cancelResult != OrderCancelResult.CANCELLED && cancelResult != OrderCancelResult.NOT_FOUND) {
            holdStopLossForTpCancel(queue, state, session, "익절 지정가 취소 실패");
            return;
        }

        // 2) 취소 후 잔여 포지션 확인
        BigDecimal remaining = tradeFacade.getPositionSize(session.getUserId(), session.getSymbol(), session.getExchangeType(), session.getTradeMode());
        if (remaining == null) {
            holdStopLossForTpCancel(queue, state, session, "익절 지정가 취소 후 포지션 조회 실패");
            return;
        }
        state.setTpCancelFailCount(0);

        if (remaining.signum() == 0) {
            // 포지션 이미 0 → 시장가 손절 생략, 거래소 청산 내역(익절 체결 등) 기준으로 전환
            session.addLog(now() + " [손절 생략] 포지션이 이미 닫혀 있어 거래소 청산 내역 기준으로 처리합니다 (큐 #" + queue.getId() + ")");
            handleCloseOrderFailure(queue, state, session, "손절 전 포지션 0 확인 후 청산 내역 처리 실패");
            return;
        }

        // 익절 지정가는 더 이상 없음 → 상태 정리 (부분 체결 이력용 값은 지역 변수로 보관)
        String tpOrderId = state.getTpOrderId();
        BigDecimal tpPrice = state.getTpOrderPrice() != null ? state.getTpOrderPrice() : state.getTpPrice();
        state.setTpOrderLinkId(null);
        state.setTpOrderId(null);
        state.setTpOrderPrice(null);

        BigDecimal entryQty = new BigDecimal(state.getEntryQty());
        BigDecimal filled = entryQty.subtract(remaining);
        if (filled.signum() <= 0) {
            // 익절 체결 없음 → 전량 손절 (기존 흐름)
            handleLiquidation(queue, state, session, pattern, liquidationDirection, currentPrice);
            return;
        }

        // 3) 부분 익절 후 손절 (D1): 레인 안에서 추정 손익으로 즉시 판단 (실값은 후처리 이력에 기록)
        // [사용자 정책 2026-10-29] 합산 손익 ≥ 0 → 익절 경로(1단계·같은 방향), < 0 → 손절 경로(다음 단계·반대 방향)
        BigDecimal entry = new BigDecimal(state.getEntryPrice());
        BigDecimal current = new BigDecimal(currentPrice);
        BigDecimal sign = state.getDirection() == Side.LONG ? BigDecimal.ONE : BigDecimal.ONE.negate();
        BigDecimal tpFee = TradeCalculator.calcFee(tpPrice, filled,
                adapterRegistry.trade(session.getExchangeType()).getMakerFeeRate());
        BigDecimal tpPnl = tpPrice.subtract(entry).multiply(filled).multiply(sign).subtract(tpFee);
        BigDecimal slPnl = current.subtract(entry).multiply(remaining).multiply(sign)
                .subtract(TradeCalculator.calcFee(current, remaining, takerFeeRate(session.getExchangeType())));
        BigDecimal sumPnl = tpPnl.add(slPnl);
        boolean takeProfitPath = sumPnl.signum() >= 0;

        BigDecimal tpPercent = filled.multiply(BigDecimal.valueOf(100)).divide(entryQty, 0, RoundingMode.HALF_UP);
        session.addLog(now() + " [부분 익절 후 손절] 부분 익절 " + tpPercent + "% / 손절 " + BigDecimal.valueOf(100).subtract(tpPercent)
                + "%, 합산 손익 " + (sumPnl.signum() >= 0 ? "+" : "") + sumPnl.setScale(2, RoundingMode.HALF_UP).toPlainString()
                + "$ (추정) → " + (takeProfitPath ? "익절 처리" : "손절 처리") + " (큐 #" + queue.getId() + ")");

        // 부분 익절분 거래 이력 (지정가 체결가·maker 추정 수수료)
        PartialTpFill partialTp = recordPartialTpFill(queue, state, session, filled, tpPrice, tpOrderId);

        // 잔여 수량만 시장가 청산, 투자 이력은 두 주문 합산 1건
        closeForStopLoss(queue, state, session, pattern, liquidationDirection, currentPrice,
                remaining.toPlainString(), partialTp, takeProfitPath);
    }

    /**
     * 취소한 익절 지정가의 부분 체결분 거래 이력(SELL)을 저장한다. (지정가 체결가·maker 추정 수수료)
     *
     * @param queue     현재 큐
     * @param state     큐 런타임 상태 (진입 정보)
     * @param session   세션
     * @param filled    익절 체결 수량 (진입 수량 − 취소 후 잔여 포지션)
     * @param tpPrice   익절 지정가 (체결가)
     * @param tpOrderId 익절 지정가 거래소 주문 ID
     * @return 투자 이력 합산용 부분 익절 정보
     */
    private PartialTpFill recordPartialTpFill(PatternQueue queue, QueueStateDTO state, AutoTradeSessionDTO session,
                                              BigDecimal filled, BigDecimal tpPrice, String tpOrderId) {
        BigDecimal tpFee = TradeCalculator.calcFee(tpPrice, filled,
                adapterRegistry.trade(session.getExchangeType()).getMakerFeeRate());
        TradeHistoryDTO tpHistory = new TradeHistoryDTO();
        tpHistory.setPatternId(state.getActivePatternId());
        tpHistory.setUserId(session.getUserId());
        tpHistory.setSymbol(session.getSymbol());
        tpHistory.setExchangeType(session.getExchangeType());
        tpHistory.setSide(state.getDirection());
        tpHistory.setOrderType(TradeOrderType.SELL.getTradeOrderType());
        tpHistory.setAmount(proportionalMargin(state.getEntryMargin(), filled.toPlainString(), state.getEntryQty()));
        tpHistory.setExecutedPrice(tpPrice);
        tpHistory.setFee(tpFee);
        tpHistory.setOrderId(tpOrderId);
        tpHistory.setOrderResult(OrderResult.SUCCESS);
        saveTradeHistoryOrFallback(tpHistory, tpOrderId, session.getTradeMode(), queue.getId());
        return new PartialTpFill(filled.toPlainString(), tpPrice, tpFee);
    }

    /**
     * 손절 전 익절 지정가 취소(또는 취소 후 포지션 조회) 실패 시 이번 틱 손절을 보류한다. (④)
     * 5회 연속 실패하면 큐를 비활성화하고 수동 확인을 안내한다.
     */
    private void holdStopLossForTpCancel(PatternQueue queue, QueueStateDTO state, AutoTradeSessionDTO session, String reason) {
        int fails = state.getTpCancelFailCount() + 1;
        state.setTpCancelFailCount(fails);
        log.warn(LogMessage.TP_CANCEL_FAILED.getMessage(), queue.getId(), reason, fails);
        if (fails >= CLOSE_CONFIRM_MAX_FAILS) {
            session.addLog(now() + " [손절 중단] " + reason + " " + CLOSE_CONFIRM_MAX_FAILS
                    + "회 연속 — 큐를 비활성화합니다. 거래소 주문·포지션을 수동으로 확인해 주세요. (큐 #" + queue.getId() + ")");
            deactivateQueue(queue, state, session, reason + " " + CLOSE_CONFIRM_MAX_FAILS + "회");
        } else if (fails == 1) {
            session.addLog(now() + " [손절 보류] " + reason + " — 잠시 후 다시 시도합니다 (큐 #" + queue.getId() + ")");
        }
    }

    /** 마진을 수량 비율로 나눈다 (부분 체결 이력의 주문 금액). */
    private BigDecimal proportionalMargin(BigDecimal margin, String qty, String totalQty) {
        if (margin == null) return null;
        return margin.multiply(new BigDecimal(qty)).divide(new BigDecimal(totalQty), 8, RoundingMode.HALF_UP);
    }

    /**
     * 손절 전 취소한 익절 지정가의 부분 체결 정보. 투자 이력을 두 주문 합산 1건으로 기록할 때 사용한다.
     */
    private record PartialTpFill(
            String qty,       // 익절 체결 수량
            BigDecimal price, // 익절 지정가 (체결가)
            BigDecimal fee    // 익절분 maker 추정 수수료
    ) {}

    /**
     * 청산 주문 접수 직후 확인 대기를 등록하고 즉시 1회 확인한다.
     * [사용자 정책 2026-10-29] 다음 동작은 이전 동작이 거래소에서 완료 확인된 뒤에만 시작한다.
     *
     * @param queue      청산한 큐
     * @param state      큐 런타임 상태 (POSITION_HOLDING 유지)
     * @param session    세션
     * @param closeType  청산 종류 (SELL: 1단계 리셋, LIQUIDATION: 다음 단계 이동)
     * @param nextSignal 손절 후 다음 단계 진입 방향 (SELL이면 null)
     */
    private void awaitCloseConfirmed(PatternQueue queue, QueueStateDTO state, AutoTradeSessionDTO session,
                                     TradeOrderType closeType, Side nextSignal) {
        state.setPendingConfirmType(closeType);
        state.setPendingCloseSignal(nextSignal);
        state.setCloseConfirmFailCount(0);
        checkPendingConfirm(queue, state, session);
    }

    /**
     * 거래소 포지션 수량으로 대기 중인 주문의 완료를 확인하고, 확인되면 상태를 전환한다.
     * - 진입(ENTRY): 포지션 있음 → 보류한 진입 이력 SUCCESS 저장 / 포지션 0 → 미체결로 FAILED 저장 후 큐 비활성화
     * - 청산(SELL/LIQUIDATION): SIM은 즉시 완료, 포지션 0 → SELL은 1단계 리셋, LIQUIDATION은 다음 단계 이동
     * - 조회 실패(청산은 포지션 잔존 포함): 보유 상태 유지, 5회 연속이면 큐 비활성화 + 수동 확인 안내
     *
     * @param queue   대상 큐
     * @param state   큐 런타임 상태 (pendingConfirmType 설정됨)
     * @param session 세션
     */
    private void checkPendingConfirm(PatternQueue queue, QueueStateDTO state, AutoTradeSessionDTO session) {
        if (state.getPendingConfirmType() == TradeOrderType.ENTRY) {
            checkPendingEntry(queue, state, session);
            return;
        }
        if (session.getTradeMode() != TradeMode.SIM) {
            state.setCloseConfirmCheckedAt(System.currentTimeMillis());
            BigDecimal positionSize = tradeFacade.getPositionSize(session.getUserId(), session.getSymbol(), session.getExchangeType(), session.getTradeMode());
            // 조회 실패(null) 또는 포지션 잔존 → 미확인
            if (positionSize == null || positionSize.signum() > 0) {
                int fails = state.getCloseConfirmFailCount() + 1;
                state.setCloseConfirmFailCount(fails);
                log.warn(LogMessage.CLOSE_NOT_CONFIRMED.getMessage(), queue.getId(), positionSize, fails);
                if (fails >= CLOSE_CONFIRM_MAX_FAILS) {
                    session.addLog(now() + " [청산 확인 실패] 거래소 포지션 청산을 " + CLOSE_CONFIRM_MAX_FAILS
                            + "회 연속 확인하지 못해 큐를 비활성화합니다. 거래소 포지션을 수동으로 확인해 주세요. (큐 #" + queue.getId() + ")");
                    deactivateQueue(queue, state, session, "청산 완료 확인 실패 " + CLOSE_CONFIRM_MAX_FAILS + "회");
                } else if (fails == 1) {
                    session.addLog(now() + " [청산 확인 대기] 거래소 포지션 청산 확인 전까지 다음 동작을 보류합니다 (큐 #" + queue.getId() + ")");
                }
                return;
            }
        }

        // 청산 완료 확인 → 대기 해제 후 상태 전환
        TradeOrderType closeType = state.getPendingConfirmType();
        Side nextSignal = state.getPendingCloseSignal();
        state.setPendingConfirmType(null);
        state.setPendingCloseSignal(null);
        state.setCloseConfirmFailCount(0);
        // 큐 종료 청산이 확인 대기 중이었으면 다음 단계로 가지 않고 큐 비활성화 (사용자가 끄기·삭제를 요청함)
        if (state.isStopRequested()) {
            session.addLog(now() + " [큐 종료 청산] 포지션 청산이 확인되어 큐를 비활성화합니다 (큐 #" + queue.getId() + ")");
            deactivateQueue(queue, state, session, "큐 종료 요청 — 포지션 청산 확인");
            return;
        }
        if (closeType == TradeOrderType.SELL) {
            resetToFirstStepAfterSell(queue, state, session);
        } else {
            moveToNextStepAfterLiquidation(queue, state, session, nextSignal);
        }
    }

    /**
     * 청산(손절) 완료 후 상태 전환: 다음 단계의 청산 방향 패턴으로 이동한다. (SRS 12)
     * 모든 단계 소진 시 cycle 설정에 따라 1단계 재시작 또는 큐 비활성화 (SRS 14)
     *
     * @param liquidationDirection 청산을 유발한 신호 방향 (다음 단계 진입 방향)
     */
    private void moveToNextStepAfterLiquidation(PatternQueue queue, QueueStateDTO state,
                                                AutoTradeSessionDTO session, Side liquidationDirection) {
        // 다음 단계로 이동
        int nextStepLevel = state.getCurrentStepLevel() + 1;
        List<Pattern> nextStepPatterns = findPatternsByLevel(queue, nextStepLevel);

        if (nextStepPatterns.isEmpty()) {
            // 모든 단계 소진 → cycle 설정에 따라 재시작 or 비활성화
            if (queue.isCycle()) {
                restartFromFirstStep(queue, state, session);
            } else {
                deactivateQueue(queue, state, session, "모든 단계 소진");
            }
            return;
        }

        // 청산 방향의 패턴 선택
        Pattern nextPattern = selectPattern(nextStepPatterns, liquidationDirection);
        if (nextPattern == null) {
            deactivateQueue(queue, state, session,
                    nextStepLevel + "단계에 " + liquidationDirection + " 패턴 없음");
            return;
        }

        // 상태 갱신: 다음 단계 + 새 패턴 + BLOCK_MATCHING (포지션 없이 블록 매칭 재시작)
        state.setCurrentStepLevel(nextStepLevel);
        state.setActivePatternId(nextPattern.getId());
        state.setDirection(liquidationDirection);
        // 첫 블록은 직전 청산 신호(liquidationDirection)로 자동 매칭 → 두 번째 블록부터 매칭
        state.setCurrentBlockOrder(2);
        state.setEntryPrice(null);
        state.setEntryQty(null);
        state.setEntryFee(null);
        state.setEntryTime(null);
        state.setTpPrice(null);
        state.setSlPrice(null);
        state.setTpOrderLinkId(null);
        state.setTpOrderId(null);
        state.setTpOrderPrice(null);
        state.setTpCancelFailCount(0);
        state.setBlockBaseTime(null);
        state.setBlockBasePrice(null);
        state.setPhase(TradePhase.BLOCK_MATCHING);

        session.addLog(now() + " [다음 단계] " + nextStepLevel + "단계 "
                + liquidationDirection + " 패턴 선택 (큐 #" + queue.getId() + ")");
    }

    /**
     * 앱의 매도/청산(reduceOnly) 주문 실패를 처리한다.
     * 거래소 TP/SL·강제청산 등으로 포지션이 이미 닫혔는데 외부 청산 감지보다 앱의 주문이 먼저 나간 경우,
     * 거래소는 줄일 포지션이 없다며 주문을 거절한다(Bybit retCode 110017, 같은 코드가 수량 0 절삭에도 쓰여 코드로는 구분 불가).
     * 그래서 에러 코드 대신 거래소 포지션을 재확인해 판단한다.
     * - 포지션 0: 진입 이후 청산 건을 찾아 외부 청산과 같은 규칙으로 전환 ({@link #processExternalClose})
     *   청산 내역 조회 실패 시 상태 유지 → 놓친 외부 청산 재조정({@link #reconcileExternalCloses})이 재시도
     * - 포지션 남아 있음·조회 실패·SIM: 실제 주문 실패로 보고 큐 비활성화 (기존 동작)
     *
     * @param queue    주문이 실패한 큐
     * @param state    큐 런타임 상태 (POSITION_HOLDING)
     * @param session  세션
     * @param reason   비활성화 시 남길 사유
     */
    private void handleCloseOrderFailure(PatternQueue queue, QueueStateDTO state,
                                         AutoTradeSessionDTO session, String reason) {
        // SIM은 거래소 포지션이 없어 null → 기존처럼 비활성화
        BigDecimal positionSize = tradeFacade.getPositionSize(session.getUserId(), session.getSymbol(), session.getExchangeType(), session.getTradeMode());
        if (positionSize == null || positionSize.signum() > 0) {
            deactivateQueue(queue, state, session, reason);
            return;
        }

        // 포지션이 이미 0 → 진입 시각 이후 청산 건 조회 (진입 시각이 없으면 최대 범위)
        long since = state.getEntryTime() != null
                ? state.getEntryTime()
                : System.currentTimeMillis() - RECONCILE_FALLBACK_LOOKBACK_MS;
        PositionCloseDTO close = tradeFacade.findLastClose(session.getUserId(), session.getSymbol(), since, session.getExchangeType(), session.getTradeMode());
        if (close == null) {
            // 청산 방식 판단 불가 → 상태 유지 (재조정 주기에 다시 확인)
            session.addLog(now() + " [경고] 포지션이 이미 닫혀 있으나 청산 내역을 확인하지 못했습니다 — 잠시 후 다시 확인합니다 (큐 #"
                    + queue.getId() + ")");
            return;
        }

        log.warn(LogMessage.CLOSE_ORDER_FAILED_ALREADY_CLOSED.getMessage(),
                queue.getId(), session.getSymbol(), close.getCloseType(), close.getOrderId());
        // 외부 청산과 같은 규칙으로 전환 (같은 심볼의 다른 보유 큐도 함께 처리됨)
        processExternalClose(session, close);
    }

    /**
     * 외부 청산 체결을 확인하고 큐 상태를 전환한다. (레인 스레드에서 실행 → 앱 자체 매도/청산과 직렬화)
     * 거래소 포지션 수량을 REST로 재확인해 0일 때만 처리하며, 조회 실패·포지션 잔존 시 상태를 유지한다.
     * 청산 방식별 전환 (사용자 확정 규칙):
     * - 거래소 TP 발동: 매도와 같이 1단계·같은 방향으로 재시작
     * - 거래소 SL 발동·강제청산: 청산과 같이 다음 단계·반대 방향으로 이동
     * - 수동 청산·ADL 등: 이력만 저장하고 큐 비활성화 (앱이 전략 의도를 판단하지 않음)
     *
     * @param session 대상 MAIN 세션
     * @param event   외부 청산 체결 이벤트
     */
    private void processExternalClose(AutoTradeSessionDTO session, PositionCloseDTO event) {
        // 포지션 보유 중인 큐만 대상 (이미 앱이 청산했거나 진입 전이면 무시)
        List<PatternQueue> holdingQueues = session.getQueues().stream()
                .filter(q -> {
                    QueueStateDTO st = session.getQueueStates().get(q.getId());
                    // 앱 진입·청산 확인 대기 중인 큐는 확인 로직이 전환하므로 제외
                    return st != null && st.getPhase() == TradePhase.POSITION_HOLDING
                            && st.getPendingConfirmType() == null;
                })
                .toList();
        if (holdingQueues.isEmpty()) return;

        // 거래소 포지션 재확인: 부분 청산·체결 지연 등으로 포지션이 남아 있으면 오판 방지를 위해 상태 유지
        BigDecimal positionSize = tradeFacade.getPositionSize(session.getUserId(), session.getSymbol(), session.getExchangeType(), session.getTradeMode());
        if (positionSize == null) {
            log.warn(LogMessage.EXTERNAL_CLOSE_CHECK_FAILED.getMessage(),
                    session.getUserId(), session.getSymbol(), event.getOrderId());
            session.addLog(now() + " [경고] 외부 청산 체결을 받았으나 포지션 확인에 실패했습니다 ("
                    + event.getCloseType().getExplain() + ") — 거래소 포지션을 확인해 주세요.");
            return;
        }
        if (positionSize.signum() > 0) {
            log.warn(LogMessage.EXTERNAL_CLOSE_IGNORED.getMessage(),
                    session.getUserId(), session.getSymbol(), positionSize, event.getOrderId());
            return;
        }

        // 단방향 모드는 같은 심볼 포지션이 합쳐지므로, 보유 큐가 여럿이면 거래소 손익 1건을 큐마다 중복 저장하지 않도록
        // 주문 ID 없이 기록 → 체결가는 이벤트 체결가, 손익은 큐별 계산식 사용
        String recordOrderId = holdingQueues.size() > 1 ? null : event.getOrderId();
        PositionCloseType closeType = event.getCloseType();
        String exitPrice = event.getExecPrice();

        for (PatternQueue queue : holdingQueues) {
            QueueStateDTO state = session.getQueueStates().get(queue.getId());
            // 우리 익절 지정가의 체결: 일반 지정가라 거래소 분류가 OTHER일 수 있어 orderLinkId로 익절 확정
            boolean ownTpFill = state.getTpOrderLinkId() != null
                    && state.getTpOrderLinkId().equals(event.getOrderLinkId());
            PositionCloseType queueCloseType = ownTpFill ? PositionCloseType.TAKE_PROFIT : closeType;
            if (ownTpFill) {
                log.info(LogMessage.TP_LIMIT_FILLED.getMessage(), queue.getId(), session.getSymbol(), event.getOrderId());
                session.addLog(now() + " [익절 체결] 익절 지정가 체결 (큐 #" + queue.getId()
                        + ", " + state.getCurrentStepLevel() + "단계)");
            } else {
                log.info(LogMessage.EXTERNAL_CLOSE_DETECTED.getMessage(),
                        queue.getId(), session.getSymbol(), closeType, event.getOrderId());
                session.addLog(now() + " [외부 청산] " + closeType.getExplain() + " 감지 (큐 #" + queue.getId()
                        + ", " + state.getCurrentStepLevel() + "단계)");
            }

            // ③ 익절 지정가 외의 청산으로 포지션이 닫혔는데 지정가가 남아 있으면 취소
            //    (남겨 두면 다음 진입 포지션을 reduceOnly로 줄일 수 있음 → 취소 실패 시 큐 비활성화)
            boolean tpCancelFailed = false;
            if (!ownTpFill && state.getTpOrderLinkId() != null) {
                OrderCancelResult cancelResult = tradeFacade.cancelOrder(session.getUserId(), session.getSymbol(),
                        state.getTpOrderLinkId(), session.getExchangeType(), session.getTradeMode());
                tpCancelFailed = cancelResult != OrderCancelResult.CANCELLED && cancelResult != OrderCancelResult.NOT_FOUND;
            }

            // 이력 저장 (상태 전환 전에 진입 정보 스냅샷)
            Pattern pattern = findPatternById(queue, state.getActivePatternId());
            String closeQty = state.getEntryQty();
            String priceForRecord = exitPrice != null ? exitPrice : state.getEntryPrice();
            if (pattern != null && closeQty != null && !"0".equals(closeQty) && priceForRecord != null) {
                TradeHistoryDTO history = new TradeHistoryDTO();
                history.setPatternId(state.getActivePatternId());
                history.setUserId(session.getUserId());
                history.setSymbol(session.getSymbol());
                history.setExchangeType(session.getExchangeType()); // 세션 거래소 기록
                history.setSide(state.getDirection());
                // 익절은 매도, 그 외(손절·강제청산·수동)는 청산으로 기록
                history.setOrderType(queueCloseType == PositionCloseType.TAKE_PROFIT
                        ? TradeOrderType.SELL.getTradeOrderType()
                        : TradeOrderType.LIQUIDATION.getTradeOrderType());
                history.setAmount(state.getEntryMargin()); // 마진(실제 투자금) 저장
                // 추정 수수료: 익절 지정가 체결은 maker, 그 외는 taker
                BigDecimal feeRate = ownTpFill
                        ? adapterRegistry.trade(session.getExchangeType()).getMakerFeeRate()
                        : takerFeeRate(session.getExchangeType());
                BigDecimal estimatedExitFee = TradeCalculator.calcFee(
                        new BigDecimal(priceForRecord), new BigDecimal(closeQty), feeRate);
                history.setFee(estimatedExitFee);
                OrderSide closeSide = state.getDirection() == Side.LONG ? OrderSide.SELL : OrderSide.BUY;
                submitCloseRecord(CloseRecord.of(recordOrderId, null, history,
                        ownTpFill ? "익절(지정가)" : closeType.getExplain(),
                        queue, state, session, pattern, closeSide, closeQty, priceForRecord, estimatedExitFee), session);
            } else {
                // 방어 코드: 진입 정보가 없어 이력을 남길 수 없음 (상태 전환은 계속)
                log.warn("외부 청산 이력 저장 건너뜀: queueId={}, patternId={}, entryQty={}",
                        queue.getId(), state.getActivePatternId(), closeQty);
                session.addLog(now() + " [경고] 외부 청산 이력을 저장할 수 없습니다 (진입 정보 없음, 큐 #" + queue.getId() + ")");
            }

            if (tpCancelFailed) {
                deactivateQueue(queue, state, session, "외부 청산 후 남은 익절 지정가 취소 실패 — 거래소 미체결 주문을 수동으로 확인해 주세요");
                continue;
            }

            // 청산 방식별 상태 전환
            switch (queueCloseType) {
                case TAKE_PROFIT -> resetToFirstStepAfterSell(queue, state, session);
                case STOP_LOSS, LIQUIDATION -> {
                    state.setEntryMargin(null); // 포지션 리셋 시 초기화 (앱 청산과 동일)
                    Side nextDirection = state.getDirection() == Side.LONG ? Side.SHORT : Side.LONG;
                    moveToNextStepAfterLiquidation(queue, state, session, nextDirection);
                }
                default -> deactivateQueue(queue, state, session,
                        "외부 청산(" + closeType.getExplain() + ") 감지 — 포지션이 앱 밖에서 닫혀 큐를 중단했습니다");
            }
        }
    }

    // ─────────────────────────────────────────────
    // 청산 후처리 (체결 조회 → 이력 저장, 비동기)
    // ─────────────────────────────────────────────

    /**
     * 청산 주문 직후의 진입 정보 스냅샷.
     * 후처리는 상태 리셋/단계 이동 이후 실행되므로 QueueStateDTO 대신 이 값을 사용한다.
     */
    private record CloseRecord(
            String orderId,             // 청산 주문 ID (SIM 또는 응답 누락 시 null)
            String orderLinkId,         // 청산 주문 orderLinkId (WS 체결 대기 키)
            TradeHistoryDTO history,    // 거래 이력 (주문 시 사용한 객체, 실체결 값으로 갱신 후 저장)
            String label,               // 로그 표기용 구분 ("매도" / "청산")
            Long queueId,               // 큐 ID (로그용)
            Long userId,                // 사용자 ID
            String symbol,              // 심볼
            TradeMode tradeMode,        // 거래 모드
            ExchangeType exchangeType,  // 거래소 타입
            Long patternId,             // 청산 대상 포지션의 패턴 ID
            Side direction,             // 청산 대상 포지션 방향
            String entryPrice,          // 진입가
            String entryQty,            // 진입 수량
            BigDecimal entryMargin,     // 진입 마진 (null이면 투자 이력 저장 생략)
            BigDecimal entryFee,        // 진입 실수수료 (null이면 추정값 재계산)
            Long entryTime,             // 진입 주문 전송 시각 (epoch ms, 손익 조회 시 펀딩비 합산 시작점)
            Pattern pattern,            // 청산 대상 패턴 (레버리지, 익절/손절 비율)
            OrderSide closeSide,        // 청산 주문 방향 (SELL/BUY)
            String closeQty,            // 청산 수량
            String currentPrice,        // 청산 판단 시점 현재가 (실체결 조회 실패 시 fallback)
            BigDecimal estimatedExitFee, // 추정 청산 수수료 (실체결 조회 실패 시 fallback)
            PartialTpFill partialTp      // 손절 전 부분 익절 체결분 (없으면 null, 있으면 투자 이력 합산)
    ) {
        /** 부분 익절 정보를 붙인 복사본 */
        CloseRecord withPartialTp(PartialTpFill p) {
            return new CloseRecord(orderId, orderLinkId, history, label, queueId, userId, symbol, tradeMode, exchangeType,
                    patternId, direction, entryPrice, entryQty, entryMargin, entryFee, entryTime,
                    pattern, closeSide, closeQty, currentPrice, estimatedExitFee, p);
        }

        static CloseRecord of(OrderResultDTO orderResult, String orderLinkId,
                              TradeHistoryDTO history, String label,
                              PatternQueue queue, QueueStateDTO state, AutoTradeSessionDTO session,
                              Pattern pattern, OrderSide closeSide, String closeQty,
                              String currentPrice, BigDecimal estimatedExitFee) {
            String orderId = orderResult != null ? orderResult.getOrderId() : null;
            return of(orderId, orderLinkId, history, label, queue, state, session,
                    pattern, closeSide, closeQty, currentPrice, estimatedExitFee);
        }

        /** 주문 ID를 직접 지정하는 생성 (외부 청산: 거래소가 만든 청산 주문 ID 사용) */
        static CloseRecord of(String orderId, String orderLinkId,
                              TradeHistoryDTO history, String label,
                              PatternQueue queue, QueueStateDTO state, AutoTradeSessionDTO session,
                              Pattern pattern, OrderSide closeSide, String closeQty,
                              String currentPrice, BigDecimal estimatedExitFee) {
            return new CloseRecord(orderId, orderLinkId, history, label, queue.getId(),
                    session.getUserId(), session.getSymbol(), session.getTradeMode(), session.getExchangeType(),
                    state.getActivePatternId(), state.getDirection(),
                    state.getEntryPrice(), state.getEntryQty(), state.getEntryMargin(), state.getEntryFee(), state.getEntryTime(),
                    pattern, closeSide, closeQty, currentPrice, estimatedExitFee, null);
        }
    }

    /**
     * 청산(매도/청산) 주문을 실행한다. 체결 push가 주문 응답보다 먼저 올 수 있어 주문 전에 WS 체결 대기를 등록하고,
     * 주문이 실패하면 대기를 정리한 뒤 예외를 다시 던진다. (성공 시 대기는 후처리에서 소비)
     *
     * @param request     청산 주문 요청
     * @param session     세션 (사용자 ID, 거래 모드)
     * @param history     거래 이력 (주문 실패 시 TradeFacade가 실패 이력 저장)
     * @param orderLinkId 주문에 지정한 orderLinkId
     * @return 주문 결과 (거래소 주문 ID)
     */
    private OrderResultDTO placeCloseOrder(OrderRequestDTO request, AutoTradeSessionDTO session,
                                           TradeHistoryDTO history, String orderLinkId) {
        tradeFacade.expectExecution(session.getUserId(), orderLinkId, session.getExchangeType(), session.getTradeMode());
        try {
            return tradeFacade.placeOrder(request, session.getUserId(), history, session.getExchangeType(), session.getTradeMode());
        } catch (RuntimeException e) {
            // 주문 실패 → 체결이 오지 않으므로 대기 정리
            tradeFacade.cancelExecution(orderLinkId, session.getExchangeType(), session.getTradeMode());
            throw e;
        }
    }

    /**
     * 청산 후처리를 전용 실행기에 제출한다.
     * 후처리 실패는 이미 청산된 포지션에 영향을 주지 않으므로 큐를 비활성화하지 않고 경고 로그만 남긴다.
     *
     * @param record  청산 시점 스냅샷
     * @param session 로그를 남길 세션
     */
    private void submitCloseRecord(CloseRecord record, AutoTradeSessionDTO session) {
        closeRecordExecutor.execute(() -> {
            try {
                recordCloseResult(record, session);
            } catch (Exception e) {
                log.error("청산 후처리 실패: queueId={}, orderId={}", record.queueId(), record.orderId(), e);
                session.addLog(now() + " [경고] " + record.label() + " 이력 저장 실패 (큐 #"
                        + record.queueId() + "): " + e.getMessage() + " — 거래소 내역을 확인해 주세요.");
                // [사용자 정책 2026-10-29] 후처리 실패도 거래 이력에 실패 행으로 남긴다
                saveFallbackHistory(record.history(), record.orderId(), record.tradeMode(), e, record.queueId());
            }
        });
    }

    /**
     * 청산 주문의 실체결 정보를 조회해 거래 이력과 투자 이력을 저장한다. (후처리 스레드에서 실행)
     * - MAIN: 실체결가·실수수료(WS 우선, 최대 2초 대기 후 REST)·실손익(펀딩비 포함, REST) 사용
     *   (WS execPnl은 수수료·펀딩비 차감 전 값이라 실손익은 REST closed-pnl을 유지)
     * - SIM 또는 조회 실패: 현재가·추정 수수료·계산식 손익 fallback
     *
     * @param r       청산 시점 스냅샷
     * @param session 로그를 남길 세션
     */
    private void recordCloseResult(CloseRecord r, AutoTradeSessionDTO session) {
        // 실체결 데이터: WS 체결 우선, 미수신 시 REST 조회 (MAIN 모드만, SIM은 null 반환)
        ExchangeType exchangeType = r.exchangeType();
        ExecutionDTO exec = null;
        if (r.orderId() != null) {
            exec = tradeFacade.getExecution(r.orderId(), r.orderLinkId(), r.symbol(), r.userId(), exchangeType, r.tradeMode());
        } else {
            // 주문 ID 없음 → 체결 조회 생략, 등록해 둔 WS 대기만 정리
            tradeFacade.cancelExecution(r.orderLinkId(), exchangeType, r.tradeMode());
        }

        String actualExitPrice = exec != null ? exec.getExecPrice() : r.currentPrice();
        BigDecimal actualExitFee = exec != null ? new BigDecimal(exec.getExecFee()) : r.estimatedExitFee();

        // 주문 성공: 실체결가 / 실수수료로 거래 이력 저장
        TradeHistoryDTO history = r.history();
        history.setExecutedPrice(new BigDecimal(actualExitPrice));
        history.setFee(actualExitFee);
        history.setOrderResult(OrderResult.SUCCESS);
        saveTradeHistory(history, r.tradeMode());

        // 투자 히스토리 저장
        String pnlText = "-";
        if (r.entryMargin() == null) {
            // 방어 코드: 정상 흐름에서는 발생하지 않으나, 예외 상황 대비
            log.warn("투자 히스토리 저장 건너뜀: entryMargin null, queueId={}", r.queueId());
            session.addLog(now() + " [경고] 투자 히스토리를 저장할 수 없습니다 (진입 금액 정보 없음)");
        } else {
            // entryFee: 진입 시 저장된 실수수료, null이면 추정값으로 재계산
            BigDecimal actualEntryFee = r.entryFee() != null
                    ? r.entryFee()
                    : TradeCalculator.calcFee(new BigDecimal(r.entryPrice()), new BigDecimal(r.entryQty()),
                            takerFeeRate(exchangeType));

            // 실손익 + 펀딩비 조회 (MAIN 모드만, SIM은 null → 계산식 fallback)
            // 직접 계산하는 거래소를 위해 진입 시각·진입 수수료까지 조회 조건에 담음
            // 부분 익절 합산은 주문 1건의 거래소 손익으로 표현할 수 없어 계산식 사용
            ClosedPnlDTO pnlInfo = r.orderId() != null && r.partialTp() == null
                    ? tradeFacade.getClosedPnl(ClosedPnlQueryDTO.builder()
                            .exchangeType(exchangeType).orderId(r.orderId()).symbol(r.symbol())
                            .entryTime(r.entryTime()).entryFee(actualEntryFee)
                            .build(), r.userId(), r.tradeMode())
                    : null;
            BigDecimal actualPnl = parseSafeBD(pnlInfo != null ? pnlInfo.getClosedPnl() : null);
            BigDecimal actualFundingFee = parseSafeBD(pnlInfo != null ? pnlInfo.getFundingFee() : null);

            // 부분 익절 후 손절: 청산가 = 수량 가중 평균, 청산 수수료 = 익절분 + 손절분
            String investExitPrice = actualExitPrice;
            BigDecimal investExitFee = actualExitFee;
            if (r.partialTp() != null) {
                BigDecimal tpQty = new BigDecimal(r.partialTp().qty());
                BigDecimal slQty = new BigDecimal(r.closeQty());
                investExitPrice = r.partialTp().price().multiply(tpQty)
                        .add(new BigDecimal(actualExitPrice).multiply(slQty))
                        .divide(tpQty.add(slQty), 8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
                investExitFee = actualExitFee.add(r.partialTp().fee());
            }
            BigDecimal profitLoss = saveInvestmentHistory(r.userId(), r.patternId(), r.symbol(), r.direction(),
                    r.entryPrice(), investExitPrice, r.entryMargin(), r.pattern(),
                    actualEntryFee, investExitFee, actualPnl, actualFundingFee, r.tradeMode(), r.exchangeType());
            pnlText = profitLoss.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "$";
        }

        session.addLog(now() + " [체결 확정] " + r.label() + " " + r.closeSide()
                + " 실체결가 " + actualExitPrice
                + " / 체결금액 " + estimateCloseValue(r.closeQty(), actualExitPrice) + "$"
                + " / 손익 " + pnlText + " (큐 #" + r.queueId() + ")");
    }

    /**
     * 청산 수량 × 가격으로 체결 금액을 계산한다. (소수 2자리, 표시용)
     */
    private String estimateCloseValue(String closeQty, String price) {
        return new BigDecimal(closeQty).multiply(new BigDecimal(price))
                .setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    // ─────────────────────────────────────────────
    // 유틸리티
    // ─────────────────────────────────────────────

    /**
     * 자동매매 세션 상태를 STOMP로 세션 소유자의 브라우저에만 push한다.
     * 브라우저에서 /user/queue/autotrade.status.{exchange}.{mode}.{symbol}을 구독하면 실시간으로 상태를 수신한다.
     *
     * <p>push되는 데이터는 기존 REST 폴링(/api/autotrade/status)과 동일한
     * {@link AutoTradeStatusResponse} 형태이므로, 프론트에서 동일한 처리 로직을 사용할 수 있다.</p>
     *
     * @param session 상태를 push할 자동매매 세션
     */
    private void pushAutoTradeStatus(AutoTradeSessionDTO session) {
        try {
            AutoTradeStatusResponse status = getStatusResponse(
                    session.getUserId(), session.getExchangeType(), session.getSymbol(), session.getTradeMode());
            // 세션 소유자(userId)에게만 전송 — 같은 심볼을 보는 다른 사용자에게 노출되지 않음
            // 거래소·모드별로 채널을 나눠 실전/모의, 거래소 간 상태가 섞이지 않게 함
            messagingTemplate.convertAndSendToUser(
                    String.valueOf(session.getUserId()), userQueue("autotrade.status", session), status);
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
    private void saveTradeHistory(TradeHistoryDTO history, TradeMode tradeMode) {
        // 모드별 테이블 분기는 TradeFacade → HistoryService 가 담당
        tradeFacade.saveTradeHistory(history, tradeMode);
    }

    /**
     * 익절가에 reduceOnly 지정가(GTC)를 걸어 둔다. (MAIN 전용 — SIM은 앱이 현재가로 익절 감시)
     * [사용자 정책 2026-10-28] 익절은 maker 체결로 수수료를 줄이기 위해 지정가로 미리 건다.
     * 가격은 tick 단위로 목표가보다 불리하지 않게 맞춘다 (LONG 매도 = 올림, SHORT 매수 = 내림).
     * 등록 실패 시 D2: FAILED 이력 → 시장가 청산 → 큐 비활성화 ({@link #failTpLimitOrder})
     *
     * @param queue        진입한 큐
     * @param state        큐 런타임 상태 (POSITION_HOLDING, entryQty·tpPrice 설정됨)
     * @param session      세션
     * @param currentPrice 실패 시 청산 추정 수수료 계산용 가격
     */
    private void placeTpLimitOrder(PatternQueue queue, QueueStateDTO state, AutoTradeSessionDTO session,
                                   String currentPrice) {
        // SIM: 주문 없이 실거래와 같은 지정가만 미리 계산 (익절 시 체결가로 사용 — 익절 순간 조회 지연 없음)
        if (session.getTradeMode() == TradeMode.SIM) {
            String tickSizeStr = marketService.getTickSize(session.getExchangeType(), session.getSymbol());
            // tickSize 조회 실패 시 null → 익절 시 익절가 그대로 사용 (SIM은 거래소 거부가 없으므로 진입 유지)
            state.setTpOrderPrice(tickSizeStr == null ? null
                    : TradeCalculator.roundTpLimitPrice(state.getDirection(), state.getTpPrice(), new BigDecimal(tickSizeStr)));
            return;
        }
        try {
            doPlaceTpLimitOrder(queue, state, session, currentPrice);
        } catch (Exception e) {
            // 예상치 못한 예외도 진입 실패로 번지지 않게 D2로 처리 (진입은 이미 거래소에서 완료됨)
            log.error("익절 지정가 처리 중 예외: queueId={}", queue.getId(), e);
            if (state.getTpOrderLinkId() == null && session.getQueueStates().containsKey(queue.getId())) {
                failTpLimitOrder(queue, state, session, null, currentPrice, e.getMessage());
            }
        }
    }

    // 익절 지정가 등록 본문 (placeTpLimitOrder에서 예외 보호)
    private void doPlaceTpLimitOrder(PatternQueue queue, QueueStateDTO state, AutoTradeSessionDTO session,
                                     String currentPrice) {

        OrderSide closeSide = state.getDirection() == Side.LONG ? OrderSide.SELL : OrderSide.BUY;
        TradeHistoryDTO history = new TradeHistoryDTO();
        history.setPatternId(state.getActivePatternId());
        history.setUserId(session.getUserId());
        history.setSymbol(session.getSymbol());
        history.setExchangeType(session.getExchangeType());
        history.setSide(state.getDirection());
        history.setOrderType(TradeOrderType.SELL.getTradeOrderType());
        history.setAmount(state.getEntryMargin());

        // 지정가 산출: tickSize 조회 실패 또는 0 이하 가격이면 주문 불가
        String tickSizeStr = marketService.getTickSize(session.getExchangeType(), session.getSymbol());
        BigDecimal limitPrice = tickSizeStr == null ? null
                : TradeCalculator.roundTpLimitPrice(state.getDirection(), state.getTpPrice(), new BigDecimal(tickSizeStr));
        if (limitPrice == null) {
            String reason = "익절 지정가 산출 불가 (tickSize=" + tickSizeStr + ", tpPrice=" + state.getTpPrice() + ")";
            history.setOrderResult(OrderResult.FAILED);
            history.setExecutedPrice(BigDecimal.ZERO);
            history.setErrorMessage(TP_LIMIT_FAILED_PREFIX + reason);
            saveTradeHistoryOrFallback(history, null, session.getTradeMode(), queue.getId());
            failTpLimitOrder(queue, state, session, null, currentPrice, reason);
            return;
        }

        String orderLinkId = newOrderLinkId(queue, "T");
        OrderRequestDTO request = OrderRequestDTO.builder()
                .symbol(session.getSymbol())
                .side(closeSide)
                .qty(state.getEntryQty())
                .price(limitPrice.toPlainString()) // 지정가 GTC
                .reduceOnly(true) // 포지션 청산 전용
                .clientOrderId(orderLinkId) // 체결 이벤트 매칭·취소 키
                .build();
        history.setFee(TradeCalculator.calcFee(limitPrice, new BigDecimal(state.getEntryQty()),
                adapterRegistry.trade(session.getExchangeType()).getMakerFeeRate()));
        try {
            OrderResultDTO result = tradeFacade.placeOrder(request, session.getUserId(), history,
                    session.getExchangeType(), session.getTradeMode());
            state.setTpOrderLinkId(orderLinkId);
            state.setTpOrderId(result != null ? result.getOrderId() : null);
            state.setTpOrderPrice(limitPrice);
            session.addLog(now() + " [익절 지정가 등록] " + closeSide + " " + state.getEntryQty()
                    + " @ " + limitPrice.toPlainString() + " (큐 #" + queue.getId() + ")");
        } catch (Exception e) {
            // 거래소 거부(ORDER_FAILED)는 TradeFacade가 FAILED 이력 저장, 그 외 예외는 여기서 저장
            boolean recorded = e instanceof CustomException ce && ce.getExceptionMessage() == ExceptionMessage.ORDER_FAILED;
            if (!recorded) {
                history.setOrderResult(OrderResult.FAILED);
                history.setExecutedPrice(BigDecimal.ZERO);
                history.setErrorMessage(TP_LIMIT_FAILED_PREFIX + e.getMessage());
                saveTradeHistoryOrFallback(history, null, session.getTradeMode(), queue.getId());
            }
            failTpLimitOrder(queue, state, session, orderLinkId, currentPrice, e.getMessage());
        }
    }

    /**
     * 익절 지정가 등록 실패 처리 (D2): 남은 지정가 취소 시도 → 보유 포지션 시장가 청산 → 큐 비활성화.
     * 응답 불명확(네트워크 오류 등)으로 지정가가 실제 접수됐을 수 있으므로 취소를 먼저 시도한다.
     * 청산 주문 실패 시 기존 청산 실패 규칙({@link #handleCloseOrderFailure})을 따른다.
     *
     * @param queue        대상 큐
     * @param state        큐 런타임 상태 (POSITION_HOLDING)
     * @param session      세션
     * @param tpLinkId     취소할 익절 지정가 주문 ID (주문 전 실패면 null)
     * @param currentPrice 청산 추정 수수료 계산용 가격
     * @param reason       실패 사유
     */
    private void failTpLimitOrder(PatternQueue queue, QueueStateDTO state, AutoTradeSessionDTO session,
                                  String tpLinkId, String currentPrice, String reason) {
        log.error(LogMessage.TP_LIMIT_ORDER_FAILED.getMessage(), queue.getId(), reason);
        session.addLog(now() + " [익절 지정가 등록 실패] " + reason + " — 포지션을 시장가로 청산하고 큐를 비활성화합니다 (큐 #"
                + queue.getId() + ")");
        if (tpLinkId != null) {
            tradeFacade.cancelOrder(session.getUserId(), session.getSymbol(), tpLinkId,
                    session.getExchangeType(), session.getTradeMode());
        }

        Pattern pattern = findPatternById(queue, state.getActivePatternId());
        OrderSide closeSide = state.getDirection() == Side.LONG ? OrderSide.SELL : OrderSide.BUY;
        String closeQty = state.getEntryQty();
        TradeHistoryDTO history = new TradeHistoryDTO();
        history.setPatternId(state.getActivePatternId());
        history.setUserId(session.getUserId());
        history.setSymbol(session.getSymbol());
        history.setExchangeType(session.getExchangeType());
        history.setSide(state.getDirection());
        history.setOrderType(TradeOrderType.LIQUIDATION.getTradeOrderType());
        history.setAmount(state.getEntryMargin());
        BigDecimal estimatedExitFee = TradeCalculator.calcFee(
                new BigDecimal(currentPrice), new BigDecimal(closeQty), takerFeeRate(session.getExchangeType()));
        history.setFee(estimatedExitFee);

        String orderLinkId = newOrderLinkId(queue, "L");
        OrderRequestDTO closeRequest = OrderRequestDTO.builder()
                .symbol(session.getSymbol())
                .side(closeSide)
                .qty(closeQty)
                .reduceOnly(true)
                .clientOrderId(orderLinkId)
                .build();
        try {
            OrderResultDTO orderResult = placeCloseOrder(closeRequest, session, history, orderLinkId);
            submitCloseRecord(CloseRecord.of(orderResult, orderLinkId, history, "청산(익절 지정가 등록 실패)", queue, state, session,
                    pattern, closeSide, closeQty, currentPrice, estimatedExitFee), session);
        } catch (Exception e) {
            session.addLog(now() + " [청산 실패] " + e.getMessage());
            handleCloseOrderFailure(queue, state, session, "익절 지정가 등록 실패 후 청산 실패: " + e.getMessage());
            return;
        }

        // 청산 완료 확인 (큐는 어차피 비활성화되므로 1회 확인 후 결과를 사유에 남김)
        BigDecimal positionSize = tradeFacade.getPositionSize(session.getUserId(), session.getSymbol(), session.getExchangeType(), session.getTradeMode());
        String closeStatus = positionSize != null && positionSize.signum() == 0
                ? "포지션 청산 완료"
                : "포지션 청산 미확인 — 거래소 포지션을 수동으로 확인해 주세요";
        deactivateQueue(queue, state, session, "익절 지정가 등록 실패 (" + reason + "), " + closeStatus);
    }

    /**
     * 진입 확인 대기 중인 큐의 거래소 포지션을 재확인한다. (MAIN 전용 — SIM은 대기에 들어가지 않음)
     *
     * @param queue   대상 큐
     * @param state   큐 런타임 상태 (pendingConfirmType = ENTRY)
     * @param session 세션
     */
    private void checkPendingEntry(PatternQueue queue, QueueStateDTO state, AutoTradeSessionDTO session) {
        state.setCloseConfirmCheckedAt(System.currentTimeMillis());
        TradeHistoryDTO history = state.getPendingEntryHistory();
        BigDecimal positionSize = tradeFacade.getPositionSize(session.getUserId(), session.getSymbol(), session.getExchangeType(), session.getTradeMode());

        // 조회 실패 → 미확인 누적, 5회 연속이면 큐 비활성화
        if (positionSize == null) {
            int fails = state.getCloseConfirmFailCount() + 1;
            state.setCloseConfirmFailCount(fails);
            log.warn(LogMessage.ENTRY_NOT_CONFIRMED.getMessage(), queue.getId(), null, fails);
            if (fails >= CLOSE_CONFIRM_MAX_FAILS) {
                session.addLog(now() + " [진입 확인 실패] 거래소 포지션을 " + CLOSE_CONFIRM_MAX_FAILS
                        + "회 연속 확인하지 못해 큐를 비활성화합니다. 거래소 포지션을 수동으로 확인해 주세요. (큐 #" + queue.getId() + ")");
                if (history != null) {
                    history.setOrderResult(OrderResult.FAILED);
                    history.setErrorMessage("[진입 확인 실패] 거래소 포지션 조회 " + CLOSE_CONFIRM_MAX_FAILS + "회 실패 — 거래소 내역을 확인해 주세요");
                    saveTradeHistoryOrFallback(history, history.getOrderId(), session.getTradeMode(), queue.getId());
                }
                deactivateQueue(queue, state, session, "진입 완료 확인 실패 " + CLOSE_CONFIRM_MAX_FAILS + "회");
            }
            return;
        }

        // 포지션 0 → 진입 미체결
        if (positionSize.signum() == 0) {
            failUnfilledEntry(queue, state, session, history);
            return;
        }

        // 포지션 확인 → 보류한 진입 이력 저장 후 대기 해제
        state.setPendingConfirmType(null);
        state.setPendingEntryHistory(null);
        state.setCloseConfirmFailCount(0);
        if (history != null) {
            history.setOrderResult(OrderResult.SUCCESS);
            saveTradeHistoryOrFallback(history, history.getOrderId(), session.getTradeMode(), queue.getId());
        }
        session.addLog(now() + " [진입 확인] 거래소 포지션 확인 완료 (큐 #" + queue.getId() + ")");
        placeTpLimitOrder(queue, state, session, state.getEntryPrice());
    }

    /**
     * 진입 주문 접수 후 거래소 포지션이 0이면 미체결로 보고 FAILED 이력 저장 후 큐를 비활성화한다.
     *
     * @param queue   대상 큐
     * @param state   큐 런타임 상태
     * @param session 세션
     * @param history 진입 이력 (null이면 저장 생략)
     */
    private void failUnfilledEntry(PatternQueue queue, QueueStateDTO state, AutoTradeSessionDTO session,
                                   TradeHistoryDTO history) {
        if (history != null) {
            history.setOrderResult(OrderResult.FAILED);
            history.setExecutedPrice(BigDecimal.ZERO); // 미체결 (실패 이력 공통 규칙)
            history.setErrorMessage("[진입 미체결] 주문 접수 후 거래소 포지션 0");
            saveTradeHistoryOrFallback(history, history.getOrderId(), session.getTradeMode(), queue.getId());
        }
        state.setEntryPrice(null);
        session.addLog(now() + " [진입 실패] 주문은 접수됐으나 거래소 포지션이 없습니다 (큐 #" + queue.getId() + ")");
        log.error(LogMessage.POSITION_ENTRY_FAILED.getMessage(), queue.getId(), "진입 미체결 (포지션 0)");
        deactivateQueue(queue, state, session, "진입 미체결: 거래소 포지션 0");
    }

    /**
     * 거래 이력을 저장하고, 실패하면 예외 로그와 함께 실패 이력(FAILED)을 fallback 저장한다.
     * 거래소 체결은 이미 완료된 상태이므로 예외를 호출 측으로 전파하지 않는다.
     *
     * @param history   저장할 거래 이력
     * @param orderId   거래소 주문 ID (fallback 이력에 기록, null 가능)
     * @param tradeMode 거래 모드
     * @param queueId   큐 ID (로그용)
     */
    private void saveTradeHistoryOrFallback(TradeHistoryDTO history, String orderId, TradeMode tradeMode, Long queueId) {
        try {
            saveTradeHistory(history, tradeMode);
        } catch (Exception e) {
            log.error("거래 이력 저장 실패: queueId={}, orderId={}", queueId, orderId, e);
            saveFallbackHistory(history, orderId, tradeMode, e, queueId);
        }
    }

    /**
     * 이력 저장 실패 시 원본 이력을 복사해 실패 이력(FAILED)으로 저장한다.
     * 원본 객체는 이미 저장 시도에 쓰였으므로 새 객체를 만든다. fallback 저장마저 실패하면 에러 로그만 남긴다.
     *
     * @param source    저장에 실패한 원본 이력 (null이면 생략)
     * @param orderId   거래소 주문 ID (원본에 없으면 이 값 사용, null 가능)
     * @param tradeMode 거래 모드
     * @param cause     원래 저장 실패 원인
     * @param queueId   큐 ID (로그용)
     */
    private void saveFallbackHistory(TradeHistoryDTO source, String orderId, TradeMode tradeMode,
                                     Exception cause, Long queueId) {
        if (source == null) return;
        try {
            TradeHistoryDTO fallback = TradeHistoryDTO.builder()
                    .patternId(source.getPatternId())
                    .userId(source.getUserId())
                    .symbol(source.getSymbol())
                    .exchangeType(source.getExchangeType())
                    .side(source.getSide())
                    .amount(source.getAmount())
                    // 체결가가 아직 없으면 0 (실패 이력 공통 규칙)
                    .executedPrice(source.getExecutedPrice() != null ? source.getExecutedPrice() : BigDecimal.ZERO)
                    .orderId(source.getOrderId() != null ? source.getOrderId() : orderId)
                    .fee(source.getFee())
                    .orderType(source.getOrderType())
                    .orderResult(OrderResult.FAILED)
                    // 실제 주문 실패와 구분되도록 접두어 표시
                    .errorMessage(HISTORY_SAVE_FAILED_PREFIX + cause.getMessage())
                    .build();
            saveTradeHistory(fallback, tradeMode);
        } catch (Exception e) {
            log.error("실패 이력(fallback) 저장도 실패: queueId={}, orderId={}", queueId, orderId, e);
        }
    }

    /**
     * 거래소 응답 문자열을 안전하게 BigDecimal로 변환한다.
     * null·빈 문자열이거나 파싱 실패 시 null을 반환한다.
     */
    private BigDecimal parseSafeBD(String value) {
        if (value == null || value.isEmpty()) return null;
        try { return new BigDecimal(value); } catch (NumberFormatException ignored) { return null; }
    }

    /**
     * 투자 히스토리를 저장한다. (포지션 진입 → 매도/청산 사이클 완료 기록)
     *
     * <p>손익 결정 우선순위:
     * <ol>
     *   <li>actualPnl != null (MAIN 모드 정상): 거래소 실손익 직접 사용 (펀딩비 포함)</li>
     *   <li>actualPnl == null (SIM 모드 또는 거래소 조회 실패): 계산식 fallback</li>
     * </ol>
     *
     * @param userId        사용자 ID
     * @param patternId     패턴 ID
     * @param symbol        코인 심볼
     * @param side          투자 방향
     * @param entryPrice    진입가 문자열 (MAIN: 실체결가, SIM: WebSocket 현재가)
     * @param exitPrice     청산가 문자열 (MAIN: 실체결가, SIM: WebSocket 현재가)
     * @param margin        마진 (실제 투자금, 레버리지 미포함)
     * @param pattern       패턴 (레버리지, 익절/손절 비율 참조)
     * @param entryFee      진입 수수료 (MAIN: 실수수료, SIM/fallback: 추정값)
     * @param exitFee       청산 수수료 (MAIN: 실수수료, SIM/fallback: 추정값)
     * @param actualPnl     거래소 실손익 (MAIN 성공 시 펀딩비 포함, 조회 실패 또는 SIM이면 null)
     * @param fundingFee    거래소 펀딩비 (MAIN 성공 시, 양수: 지급, 음수: 수취, 조회 실패 또는 SIM이면 null)
     * @param tradeMode     거래 모드 (MAIN: investment_history, SIM: sim_investment_history)
     * @param exchangeType  거래소 타입 (세션 거래소)
     * @return 저장된 손익 (거래소 실손익 또는 계산식 fallback)
     */
    private BigDecimal saveInvestmentHistory(Long userId, Long patternId, String symbol,
                                       Side side, String entryPrice, String exitPrice,
                                       BigDecimal margin, Pattern pattern,
                                       BigDecimal entryFee, BigDecimal exitFee,
                                       BigDecimal actualPnl, BigDecimal fundingFee, TradeMode tradeMode,
                                       ExchangeType exchangeType) {
        BigDecimal entry = new BigDecimal(entryPrice);
        BigDecimal exit = new BigDecimal(exitPrice);
        int leverage = pattern.getLeverage();

        // 총손익 계산 (TradeCalculator 위임) — SIM 지갑 반영 및 실손익 미조회 시 fallback용
        BigDecimal grossProfitLoss = TradeCalculator.calcGrossProfitLoss(side, entry, exit, margin, leverage);

        // 손익 결정: MAIN 정상 → 거래소 실손익(펀딩비 포함), SIM 또는 조회 실패 → 계산식 fallback
        BigDecimal profitLoss = actualPnl != null
                ? actualPnl
                : grossProfitLoss.subtract(entryFee.add(exitFee));

        // 익절/손절 예상가 계산 — 진입가 기준, rate는 투자금 기준 (%), 미설정 시 가격 8%
        BigDecimal tpPrice = TradeCalculator.calcTpPrice(side, entry,
                TradeCalculator.resolveRoiRate(pattern.getTakeProfitRate(), leverage), leverage);
        BigDecimal slPrice = TradeCalculator.calcSlPrice(side, entry,
                TradeCalculator.resolveRoiRate(pattern.getStopLossRate(), leverage), leverage);

        InvestmentHistoryDTO investmentHistory = InvestmentHistoryDTO.builder()
                .userId(userId)
                .patternId(patternId)
                .symbol(symbol)
                .side(side)
                .entryPrice(entry)
                .exitPrice(exit)
                .amount(margin)
                .leverage(leverage)
                .tpPrice(tpPrice)
                .slPrice(slPrice)
                .profitLoss(profitLoss)
                .fundingFee(fundingFee) // MAIN: 실펀딩비, SIM/fallback: null
                .exchangeType(exchangeType)
                .build();
        // SIM: actualPnl은 항상 null → profitLoss는 계산식 fallback 값
        tradeFacade.saveInvestmentHistory(investmentHistory, tradeMode);

        if (tradeMode == TradeMode.SIM) {
            // 모의 지갑 반영: 진입 수수료는 placeOrder 시 이미 차감 → 청산 수수료만 반영
            BigDecimal walletProfitLoss = grossProfitLoss.subtract(exitFee);
            tradeFacade.applyProfitLoss(userId, margin, walletProfitLoss, tradeMode);
        }

        log.info(LogMessage.INVESTMENT_HISTORY_SAVED.getMessage(), symbol, side, profitLoss, tradeMode);
        return profitLoss;
    }

    /**
     * 단계 내에서 주어진 방향의 첫 번째 조건 블록이 일치하는 패턴을 선택한다.
     *
     * @param stepPatterns 대상 단계의 패턴 목록
     * @param direction    매매 방향
     * @return 일치하는 패턴, 없으면 null
     */
    Pattern selectPattern(List<Pattern> stepPatterns, Side direction) {
        for (Pattern pattern : stepPatterns) {
            // 첫 번째 조건 블록 = 첫 원소 (블록이 1개뿐이면 leaf만 있어 조건 블록 없음)
            List<Side> blocks = pattern.getBlocks();
            if (blocks.size() > 1 && blocks.get(0) == direction) {
                return pattern;
            }
        }
        return null;
    }

    /**
     * 모든 단계 소진 시 1단계 TRIGGER_WAIT 상태로 큐를 재시작한다.
     * cycle=true 설정된 큐에서만 호출된다.
     */
    private void restartFromFirstStep(PatternQueue queue, QueueStateDTO state,
                                      AutoTradeSessionDTO session) {
        // 런타임 상태를 1단계 TRIGGER_WAIT으로 초기화
        // 새 인스턴스 생성 대신 reset()으로 기존 객체를 재사용 — 처리 중인 참조와 맵의 상태 객체 일치 유지
        state.reset();

        session.addLog(now() + " [사이클 재시작] 모든 단계 소진 → 1단계 재시작 (큐 #" + queue.getId() + ")");
        log.info("자동매매 사이클 재시작: queueId={}, symbol={}", queue.getId(), session.getSymbol());
    }

    /**
     * 큐 끄기·삭제 전에 그 큐의 보유 포지션을 청산한다. (D4)
     * [사용자 정책 2026-10-29] 보유 중인 큐를 끄거나 삭제하면 익절 지정가 취소 → 시장가 전량 청산 → 이력 기록 후 진행한다.
     * 틱 처리와 경쟁하지 않도록 세션 레인에서 실행하고 완료까지 기다린다.
     * 청산이 거래소에서 확인되면 큐를 세션에서 분리한다. (DB 변경·세션 동기화는 호출 측이 수행)
     * 실행 중인 세션에 큐가 없거나(비활성) 포지션을 보유하지 않았으면 아무것도 하지 않는다.
     *
     * @param userId  사용자 ID
     * @param queueId 끄거나 삭제할 큐 ID
     * @throws CustomException QUEUE_POSITION_CLOSE_FAILED — 취소·청산 실패, 청산 미확인, 처리 대기 시간 초과 (큐 유지)
     */
    public void closeQueuePosition(Long userId, Long queueId) {
        AutoTradeSessionDTO session = activeSessions.values().stream()
                .filter(s -> s.getUserId().equals(userId)
                        && s.getQueues().stream().anyMatch(q -> q.getId().equals(queueId)))
                .findFirst()
                .orElse(null);
        // 실행 중이 아닌 큐 → 엔진이 보유한 포지션 없음
        if (session == null) return;

        CompletableFuture<Void> done = new CompletableFuture<>();
        session.getPendingCommands().add(() -> {
            // 대기 시간 초과로 이미 실패 응답한 명령은 실행하지 않음
            if (done.isDone()) return;
            try {
                closeQueuePositionInLane(session, queueId);
                done.complete(null);
            } catch (Exception e) {
                done.completeExceptionally(e);
            }
        });
        scheduleLane(session);

        try {
            done.get(QUEUE_CLOSE_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // 레인이 아직 명령을 꺼내지 않았으면 실행되지 않도록 완료 처리
            done.completeExceptionally(e);
            throw queueCloseFailed("처리 대기 시간 초과");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            done.completeExceptionally(e);
            throw queueCloseFailed("처리 대기 중단");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof CustomException ce) throw ce;
            throw queueCloseFailed(String.valueOf(e.getCause().getMessage()));
        }
    }

    /**
     * 큐 종료 청산 본문 (레인 스레드에서 실행).
     * - 진입 확인 대기 중: 포지션 상태가 불확실하므로 실패 (잠시 후 재시도)
     * - 청산 확인 대기 중: 새 주문 없이 포지션 0만 확인
     * - 익절 지정가 취소 실패: 청산하지 않고 실패
     * - 취소 후 포지션 0 (익절 전량 체결·거래소 청산): 거래소 청산 내역 기준으로 처리 후 분리
     * - 부분 익절: 체결분 SELL 이력 기록 후 잔여 수량만 시장가 청산
     *
     * @param session 큐가 속한 세션
     * @param queueId 대상 큐 ID
     */
    private void closeQueuePositionInLane(AutoTradeSessionDTO session, Long queueId) {
        PatternQueue queue = session.getQueues().stream()
                .filter(q -> q.getId().equals(queueId))
                .findFirst()
                .orElse(null);
        QueueStateDTO state = queue == null ? null : session.getQueueStates().get(queueId);
        // 대기 중 비활성화됐거나 포지션 미보유 → 청산할 것 없음
        if (state == null || state.getPhase() != TradePhase.POSITION_HOLDING) return;

        // 이미 거래소 확인 대기 중인 주문이 있으면 새 주문을 내지 않음
        if (state.getPendingConfirmType() == TradeOrderType.ENTRY) {
            throw queueCloseFailed("진입 체결 확인 중 — 잠시 후 다시 시도해 주세요");
        }
        if (state.getPendingConfirmType() != null) {
            confirmQueueClosed(queue, state, session);
            return;
        }

        // 1) 익절 지정가 취소 (MAIN만 존재) — NOT_FOUND(이미 전량 체결·취소)도 주문이 없다는 뜻이므로 진행
        boolean hadTpOrder = state.getTpOrderLinkId() != null;
        if (hadTpOrder) {
            OrderCancelResult cancelResult = tradeFacade.cancelOrder(session.getUserId(), session.getSymbol(),
                    state.getTpOrderLinkId(), session.getExchangeType(), session.getTradeMode());
            if (cancelResult != OrderCancelResult.CANCELLED && cancelResult != OrderCancelResult.NOT_FOUND) {
                session.addLog(now() + " [큐 종료 청산 실패] 익절 지정가 취소 실패 — 큐를 유지합니다 (큐 #" + queue.getId() + ")");
                throw queueCloseFailed("익절 지정가 취소 실패");
            }
        }

        // 2) 청산 수량: MAIN은 거래소 잔여 포지션, SIM은 진입 수량
        BigDecimal remaining;
        if (session.getTradeMode() == TradeMode.SIM) {
            remaining = new BigDecimal(state.getEntryQty());
        } else {
            remaining = tradeFacade.getPositionSize(session.getUserId(), session.getSymbol(), session.getExchangeType(), session.getTradeMode());
            if (remaining == null) {
                throw queueCloseFailed("거래소 포지션 조회 실패");
            }
            if (remaining.signum() == 0) {
                // 포지션 이미 0 → 시장가 청산 생략, 거래소 청산 내역(익절 체결 등) 기준으로 전환한 뒤 분리
                session.addLog(now() + " [큐 종료 청산] 포지션이 이미 닫혀 있어 거래소 청산 내역 기준으로 처리합니다 (큐 #" + queue.getId() + ")");
                handleCloseOrderFailure(queue, state, session, "큐 종료 전 포지션 0 확인 후 청산 내역 처리 실패");
                // 청산 내역을 찾지 못해 보유 상태가 유지되면 실패 (재조정 주기에 다시 확인됨)
                QueueStateDTO after = session.getQueueStates().get(queueId);
                if (after != null && after.getPhase() == TradePhase.POSITION_HOLDING) {
                    throw queueCloseFailed("거래소 청산 내역 확인 실패 — 잠시 후 다시 시도해 주세요");
                }
                detachQueue(session, queueId);
                return;
            }
        }

        String currentPrice = currentPriceOf(session);
        if (currentPrice == null) {
            throw queueCloseFailed("현재가 조회 실패");
        }

        // 익절 지정가는 더 이상 없음 → 상태 정리 (부분 체결 이력용 값은 지역 변수로 보관)
        String tpOrderId = state.getTpOrderId();
        BigDecimal tpPrice = state.getTpOrderPrice() != null ? state.getTpOrderPrice() : state.getTpPrice();
        state.setTpOrderLinkId(null);
        state.setTpOrderId(null);
        state.setTpOrderPrice(null);

        // 익절 지정가가 일부 체결됐으면 체결분 익절 이력 기록 (투자 이력은 청산과 합산 1건)
        String closeQty = remaining.stripTrailingZeros().toPlainString();
        BigDecimal filled = new BigDecimal(state.getEntryQty()).subtract(remaining);
        PartialTpFill partialTp = hadTpOrder && filled.signum() > 0
                ? recordPartialTpFill(queue, state, session, filled, tpPrice, tpOrderId)
                : null;

        // 3) 잔여 포지션 시장가 전량 청산 (진입 방향의 반대, reduceOnly)
        Pattern pattern = findPatternById(queue, state.getActivePatternId());
        OrderSide closeSide = state.getDirection() == Side.LONG ? OrderSide.SELL : OrderSide.BUY;
        TradeHistoryDTO history = new TradeHistoryDTO();
        history.setPatternId(state.getActivePatternId());
        history.setUserId(session.getUserId());
        history.setSymbol(session.getSymbol());
        history.setExchangeType(session.getExchangeType());
        history.setSide(state.getDirection());
        history.setOrderType(TradeOrderType.LIQUIDATION.getTradeOrderType());
        // 마진(실제 투자금) 저장, 부분 익절 후면 잔여 수량 비율만큼
        history.setAmount(partialTp != null
                ? proportionalMargin(state.getEntryMargin(), closeQty, state.getEntryQty())
                : state.getEntryMargin());
        BigDecimal estimatedExitFee = TradeCalculator.calcFee(
                new BigDecimal(currentPrice), remaining, takerFeeRate(session.getExchangeType()));
        history.setFee(estimatedExitFee);

        String orderLinkId = newOrderLinkId(queue, "L");
        OrderRequestDTO closeRequest = OrderRequestDTO.builder()
                .symbol(session.getSymbol())
                .side(closeSide)
                .qty(closeQty)
                .reduceOnly(true) // 포지션 청산 전용
                .clientOrderId(orderLinkId)
                .build();
        try {
            OrderResultDTO orderResult = placeCloseOrder(closeRequest, session, history, orderLinkId);
            submitCloseRecord(CloseRecord.of(orderResult, orderLinkId, history, "청산(큐 종료)", queue, state, session,
                    pattern, closeSide, closeQty, currentPrice, estimatedExitFee).withPartialTp(partialTp), session);
        } catch (Exception e) {
            session.addLog(now() + " [큐 종료 청산 실패] " + e.getMessage() + " — 큐를 유지합니다 (큐 #" + queue.getId() + ")");
            throw queueCloseFailed("청산 주문 실패: " + e.getMessage());
        }
        state.setEntryMargin(null); // 포지션 리셋 시 초기화
        session.addLog(now() + " [큐 종료 청산] " + closeSide + " " + estimateCloseValue(closeQty, currentPrice)
                + "$ (추정) (큐 #" + queue.getId() + ")");

        // 4) 거래소 청산 완료 확인 후 분리
        state.setPendingConfirmType(TradeOrderType.LIQUIDATION);
        state.setPendingCloseSignal(null);
        state.setCloseConfirmFailCount(0);
        confirmQueueClosed(queue, state, session);
    }

    /**
     * 큐 종료 청산의 거래소 포지션 0을 확인하고, 확인되면 큐를 세션에서 분리한다. (SIM은 즉시 분리)
     * 미확인이면 종료 요청을 표시하고 실패시킨다 → 이후 틱의 확인 로직이 청산을 확인하면 큐를 비활성화한다.
     */
    private void confirmQueueClosed(PatternQueue queue, QueueStateDTO state, AutoTradeSessionDTO session) {
        if (session.getTradeMode() != TradeMode.SIM) {
            state.setCloseConfirmCheckedAt(System.currentTimeMillis());
            BigDecimal positionSize = tradeFacade.getPositionSize(session.getUserId(), session.getSymbol(), session.getExchangeType(), session.getTradeMode());
            if (positionSize == null || positionSize.signum() > 0) {
                state.setStopRequested(true);
                session.addLog(now() + " [큐 종료 청산 확인 대기] 거래소 포지션 청산을 아직 확인하지 못했습니다"
                        + " — 확인되면 큐를 비활성화합니다 (큐 #" + queue.getId() + ")");
                throw queueCloseFailed("거래소 포지션 청산 미확인 — 확인되면 큐가 자동으로 비활성화됩니다");
            }
        }
        session.addLog(now() + " [큐 종료 청산] 포지션 청산 확인 — 큐를 종료합니다 (큐 #" + queue.getId() + ")");
        detachQueue(session, queue.getId());
    }

    /** 세션의 현재가 (시세 캐시 기준, 없으면 null) */
    private String currentPriceOf(AutoTradeSessionDTO session) {
        TickerDTO ticker = marketService.getTicker(session.getExchangeType(), session.getSymbol());
        return ticker != null ? ticker.getLastPrice() : null;
    }

    private CustomException queueCloseFailed(String detail) {
        return new CustomException(ExceptionMessage.QUEUE_POSITION_CLOSE_FAILED, detail);
    }

    /**
     * 큐를 세션에서만 분리한다. (DB 활성 상태는 호출 측이 변경)
     * 세션에 큐가 남지 않으면 세션도 정리한다.
     */
    private void detachQueue(AutoTradeSessionDTO session, Long queueId) {
        session.getQueues().removeIf(q -> q.getId().equals(queueId));
        session.getQueueStates().remove(queueId);
        if (session.getQueues().isEmpty()) {
            activeSessions.remove(sessionKey(session.getUserId(), session.getExchangeType(), session.getSymbol(), session.getTradeMode()));
            syncWebSocketSubscriptions();
        }
    }

    /**
     * 큐를 비활성화하고 세션에서 제거한다.
     * 사용자에게 즉시 알림이 필요하므로 error 로그 + STOMP 알림 토픽 push를 함께 수행한다.
     */
    private void deactivateQueue(PatternQueue queue, QueueStateDTO state,
                                 AutoTradeSessionDTO session, String reason) {
        queue.setActive(false);
        patternQueueRepository.save(queue);

        // 세션에서 큐 제거
        session.getQueues().removeIf(q -> q.getId().equals(queue.getId()));
        session.getQueueStates().remove(queue.getId());

        session.addLog(now() + " [큐 비활성화] 큐 #" + queue.getId() + " - " + reason);
        // 비활성화는 사용자가 즉시 인지해야 하는 이벤트이므로 error 레벨로 기록
        log.error(LogMessage.QUEUE_DEACTIVATED.getMessage(), queue.getId(), reason);

        // 사용자에게 즉시 알림 (프론트에서 alert으로 표시)
        pushAutoTradeAlert(session, queue.getId(), reason);

        // 모든 큐가 제거되면 세션 정리
        if (session.getQueues().isEmpty()) {
            activeSessions.remove(sessionKey(session.getUserId(), session.getExchangeType(), session.getSymbol(), session.getTradeMode()));
            syncWebSocketSubscriptions();
        }
    }

    /**
     * 자동매매 큐 비활성화 등 즉시 사용자 알림이 필요한 이벤트를 STOMP로 push한다.
     * 세션 소유자에게만 전송하며, 프론트는 /user/queue/autotrade.alert.{exchange}.{mode}.{symbol}을 구독해 alert으로 표시한다.
     *
     * @param session 알림 대상 자동매매 세션
     * @param queueId 비활성화된 큐 ID
     * @param reason  비활성화 사유 (사용자 표시용 메시지)
     */
    private void pushAutoTradeAlert(AutoTradeSessionDTO session, Long queueId, String reason) {
        String symbol = session.getSymbol();
        try {
            AutoTradeAlertResponse alert = AutoTradeAlertResponse.builder()
                    .queueId(queueId)
                    .symbol(symbol)
                    .reason(reason)
                    .level("ERROR")
                    .occurredAt(now())
                    .build();
            messagingTemplate.convertAndSendToUser(
                    String.valueOf(session.getUserId()), userQueue("autotrade.alert", session), alert);
        } catch (Exception e) {
            log.warn("자동매매 알림 push 실패: symbol={}, error={}", symbol, e.getMessage());
        }
    }

    /**
     * 큐에서 stepLevel에 해당하는 단계의 패턴 목록을 찾는다.
     *
     * @return 해당 단계의 패턴 목록 (빈 목록이면 단계 없음)
     */
    private List<Pattern> findPatternsByLevel(PatternQueue queue, int stepLevel) {
        return queue.getPatterns().stream()
                .filter(p -> p.getStepLevel() == stepLevel)
                .toList();
    }

    /**
     * 큐 전체에서 패턴 ID로 패턴을 찾는다.
     */
    private Pattern findPatternById(PatternQueue queue, Long patternId) {
        if (patternId == null) {
            return null;
        }
        return queue.getPatterns().stream()
                .filter(p -> p.getId().equals(patternId))
                .findFirst()
                .orElse(null);
    }

    /**
     * 패턴에서 blockOrder(1부터 시작)에 해당하는 블록의 방향을 찾는다.
     *
     * @return 블록 방향, 범위를 벗어나면 null
     */
    private Side findBlockSide(Pattern pattern, int blockOrder) {
        List<Side> blocks = pattern.getBlocks();
        if (blockOrder < 1 || blockOrder > blocks.size()) {
            return null;
        }
        return blocks.get(blockOrder - 1);
    }

    /**
     * blockOrder가 leaf 블록(블록 목록의 마지막) 위치인지 판단한다.
     */
    private boolean isLeafOrder(Pattern pattern, int blockOrder) {
        return blockOrder == pattern.getBlocks().size();
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
     * 활성 세션의 심볼 목록과 WebSocket 구독을 거래소별로 동기화한다.
     * private 스트림 지원 어댑터는 활성 MAIN 세션 보유 사용자로 체결 수신 연결도 동기화한다.
     * 컨트롤러 스레드(syncSession)와 레인 스레드(deactivateQueue)가 동시에 호출할 수 있어,
     * 오래된 집합이 나중에 반영되지 않도록 집합 계산과 동기화를 한 번에 묶는다.
     */
    private synchronized void syncWebSocketSubscriptions() {
        // 거래소 타입별로 활성 심볼을 그룹핑하여 각 어댑터에 동기화
        Map<ExchangeType, Set<String>> symbolsByExchange = activeSessions.values().stream()
                .collect(Collectors.groupingBy(
                        AutoTradeSessionDTO::getExchangeType,
                        Collectors.mapping(AutoTradeSessionDTO::getSymbol, Collectors.toSet())
                ));

        // 거래소 타입별 활성 MAIN 세션 사용자 (SIM은 실주문이 없어 체결 수신 불필요)
        Map<ExchangeType, Set<Long>> mainUsersByExchange = activeSessions.values().stream()
                .filter(s -> s.getTradeMode() == TradeMode.MAIN)
                .collect(Collectors.groupingBy(
                        AutoTradeSessionDTO::getExchangeType,
                        Collectors.mapping(AutoTradeSessionDTO::getUserId, Collectors.toSet())
                ));

        // 시세 스트림: 거래소별 활성 심볼만 구독
        for (ExchangeMarketClient market : adapterRegistry.markets()) {
            market.syncSubscriptions(symbolsByExchange.getOrDefault(market.getExchangeType(), Set.of()));
        }

        // private 스트림: 거래소별 MAIN 세션 사용자만 연결 (미지원 거래소는 no-op, REST 체결 조회만 사용)
        for (ExchangeTradeClient trade : adapterRegistry.trades()) {
            trade.syncPrivateStreams(mainUsersByExchange.getOrDefault(trade.getExchangeType(), Set.of()));
        }
    }

    /**
     * 거래소의 taker 수수료율을 반환한다. (추정 수수료 계산용, SIM도 선택 거래소 기준)
     */
    private BigDecimal takerFeeRate(ExchangeType exchangeType) {
        return adapterRegistry.trade(exchangeType).getTakerFeeRate();
    }

    /**
     * 세션 소유자 전용 STOMP 목적지를 만든다. (브라우저 구독 경로는 앞에 /user 가 붙음)
     *
     * @param channel 채널 이름 (autotrade.status / autotrade.alert)
     * @param session 자동매매 세션
     * @return 예: /queue/autotrade.status.BYBIT.MAIN.BTCUSDT
     */
    private String userQueue(String channel, AutoTradeSessionDTO session) {
        return "/queue/" + channel + "." + session.getExchangeType() + "." + session.getTradeMode() + "." + session.getSymbol();
    }

    /**
     * 세션 키를 생성한다. 형식: "userId:exchangeType:symbol:tradeMode"
     */
    private String sessionKey(Long userId, ExchangeType exchangeType, String symbol, TradeMode tradeMode) {
        return userId + ":" + exchangeType.name() + ":" + symbol + ":" + tradeMode.name();
    }

    private String now() {
        return LocalDateTime.now().format(TIME_FMT);
    }
}
