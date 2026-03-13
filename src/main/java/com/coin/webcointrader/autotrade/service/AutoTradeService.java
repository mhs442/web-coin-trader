package com.coin.webcointrader.autotrade.service;

import com.coin.webcointrader.autotrade.dto.AutoTradeSessionDTO;
import com.coin.webcointrader.autotrade.repository.QueueRepository;
import com.coin.webcointrader.autotrade.repository.TradeHistoryRepository;
import com.coin.webcointrader.common.client.market.BybitWebSocketClient;
import com.coin.webcointrader.common.dto.request.CreateOrderRequest;
import com.coin.webcointrader.common.dto.response.FindTickerResponse;
import com.coin.webcointrader.common.entity.Queue;
import com.coin.webcointrader.common.entity.QueueStep;
import com.coin.webcointrader.common.entity.Side;
import com.coin.webcointrader.common.enums.Category;
import com.coin.webcointrader.common.entity.TradeHistory;
import com.coin.webcointrader.market.service.MarketService;
import com.coin.webcointrader.trade.service.TradeService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.coin.webcointrader.common.enums.OrderResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 자동매매 엔진.
 * WebSocket 실시간 가격 수신으로 이벤트 드리븐 매매를 실행하며,
 * 1초 주기 스케줄러는 WebSocket 장애 대비 안전망(fallback)으로 유지한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AutoTradeService {
    private final QueueRepository queueRepository;
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
        // WebSocket 실시간 가격 수신 리스너 등록
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
        List<Queue> activeQueues = queueRepository
                .findByUserIdAndSymbolAndUseYnAndDelYnOrderBySortOrderAsc(userId, symbol, "Y", "N");

        String key = sessionKey(userId, symbol);

        if (activeQueues.isEmpty()) {
            if (activeSessions.remove(key) != null) {
                log.info("자동매매 중지: userId={}, symbol={} (활성 큐 없음)", userId, symbol);
            }
        } else {
            AutoTradeSessionDTO existing = activeSessions.get(key);
            if (existing != null) {
                existing.setQueues(activeQueues);
                if (existing.getCurrentQueueIndex() >= activeQueues.size()) {
                    existing.setCurrentQueueIndex(0);
                    existing.setCurrentStepIndex(0);
                }
                existing.addLog(now() + " [갱신] 활성 큐 " + activeQueues.size() + "개로 갱신");
                log.info("자동매매 세션 갱신: userId={}, symbol={}, 큐 {}개", userId, symbol, activeQueues.size());
            } else {
                AutoTradeSessionDTO session = new AutoTradeSessionDTO();
                session.setUserId(userId);
                session.setSymbol(symbol);
                session.setQueues(activeQueues);
                session.setCurrentQueueIndex(0);
                session.setCurrentStepIndex(0);
                session.setPreviousPrice(null);
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
     * 1초 주기로 실행되는 자동매매 안전망(fallback).
     * WebSocket 장애 시에도 REST 폴링으로 매매가 지속되도록 한다.
     * 캐시된 티커 데이터를 기반으로 모든 활성 세션을 순회하며 processSession()을 호출한다.
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

    /**
     * WebSocket 가격으로 개별 세션을 처리한다.
     * 직전가 대비 현재가를 비교하여 신호를 판별하고 주문을 실행한다.
     */
    private void processSessionWithPrice(AutoTradeSessionDTO session, String currentPrice) {
        if (currentPrice == null) {
            return;
        }

        // 초기 가격 설정
        if (session.getPreviousPrice() == null) {
            session.setPreviousPrice(currentPrice);
            session.addLog(now() + " [시작] 초기 가격: " + currentPrice);
            return;
        }

        double current = Double.parseDouble(currentPrice);
        double previous = Double.parseDouble(session.getPreviousPrice());
        session.setPreviousPrice(currentPrice);

        // 가격 변동 없으면 무시
        if (current == previous) {
            return;
        }

        // 신호 판별 및 주문 처리
        Side signal = current > previous ? Side.LONG : Side.SHORT;
        processSignal(session, signal, currentPrice);
    }

    /**
     * REST 폴링 티커로 개별 세션의 자동매매 로직을 처리한다. (fallback용)
     * 직전가 대비 현재가를 비교하여 Long/Short 신호를 판별하고,
     * 현재 큐의 단계와 일치하면 주문을 실행, 불일치하면 다음 큐로 전환한다.
     */
    private void processSession(AutoTradeSessionDTO session, List<FindTickerResponse.TickerInfo> tickers) {
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

        if (session.getPreviousPrice() == null) {
            session.setPreviousPrice(currentPrice);
            session.addLog(now() + " [시작] 초기 가격: " + currentPrice);
            return;
        }

        double current = Double.parseDouble(currentPrice);
        double previous = Double.parseDouble(session.getPreviousPrice());
        session.setPreviousPrice(currentPrice);

        if (current == previous) {
            return;
        }

        Side signal = current > previous ? Side.LONG : Side.SHORT;
        processSignal(session, signal, currentPrice);
    }

    /**
     * 신호에 따라 현재 큐 단계와 매칭하고 주문을 실행한다.
     * 신호가 현재 단계와 일치하면 주문, 불일치하면 다른 큐로 전환 시도.
     */
    private void processSignal(AutoTradeSessionDTO session, Side signal, String currentPrice) {
        Queue currentQueue = session.getQueues().get(session.getCurrentQueueIndex());
        QueueStep expectedStep = currentQueue.getSteps().get(session.getCurrentStepIndex());

        if (signal == expectedStep.getSide()) {
            // 신호와 단계 일치 → 주문 실행
            executeOrder(session, currentQueue, expectedStep, currentPrice);
        } else {
            // 신호 불일치 → 다른 큐에서 매칭 시도
            boolean found = false;
            int queueCount = session.getQueues().size();
            for (int i = 1; i <= queueCount; i++) {
                int candidateIdx = (session.getCurrentQueueIndex() + i) % queueCount;
                Queue candidate = session.getQueues().get(candidateIdx);
                QueueStep firstStep = candidate.getSteps().get(0);

                if (firstStep.getSide() == signal) {
                    session.setCurrentQueueIndex(candidateIdx);
                    session.setCurrentStepIndex(0);
                    session.addLog(now() + " [전환] 큐 #" + candidate.getId() + "으로 전환");
                    executeOrder(session, candidate, firstStep, currentPrice);
                    found = true;
                    break;
                }
            }

            if (!found) {
                session.addLog(now() + " [대기] 신호 " + signal + " - 일치하는 큐 없음");
            }
        }
    }

    /**
     * Bybit에 시장가 주문을 실행하고, TradeHistory에 기록한다.
     */
    private void executeOrder(AutoTradeSessionDTO session, Queue queue, QueueStep step, String currentPrice) {
        String side = step.getSide() == Side.LONG ? "Buy" : "Sell";
        String qty = step.getQuantity().toPlainString();

        CreateOrderRequest request = CreateOrderRequest.builder()
                .category(Category.LINEAR.getCategory())
                .symbol(session.getSymbol())
                .side(side)
                .orderType("Market")
                .qty(qty)
                .build();

        TradeHistory history = new TradeHistory();
        history.setQueueStepId(step.getId());
        history.setUserId(session.getUserId());
        history.setSymbol(session.getSymbol());
        history.setSide(step.getSide());
        history.setQuantity(step.getQuantity());

        try {
            tradeService.placeOrder(request, session.getUserId());
            history.setExecutedPrice(new BigDecimal(currentPrice));
            history.setOrderResult(OrderResult.SUCCESS);

            String logMsg = now() + " [주문] " + side + " " + qty + " " + session.getSymbol()
                    + " (큐: #" + queue.getId()
                    + ", 단계: " + (session.getCurrentStepIndex() + 1) + "/" + queue.getSteps().size() + ")";
            session.addLog(logMsg);
            log.info(logMsg);

            // 다음 단계로 이동
            session.setCurrentStepIndex(session.getCurrentStepIndex() + 1);

            // 큐 완료 → 다음 큐로
            if (session.getCurrentStepIndex() >= queue.getSteps().size()) {
                session.addLog(now() + " [완료] 큐 #" + queue.getId() + " 완료");
                int nextIdx = (session.getCurrentQueueIndex() + 1) % session.getQueues().size();
                session.setCurrentQueueIndex(nextIdx);
                session.setCurrentStepIndex(0);
            }
        } catch (Exception e) {
            history.setExecutedPrice(BigDecimal.ZERO);
            history.setOrderResult(OrderResult.FAILED);
            history.setErrorMessage(e.getMessage());

            String errMsg = now() + " [주문실패] " + side + " " + qty + " - " + e.getMessage();
            session.addLog(errMsg);
            log.error(errMsg, e);
        }

        tradeHistoryRepository.save(history);
    }

    /**
     * 활성 세션의 심볼 목록과 WebSocket 구독을 동기화한다.
     */
    private void syncWebSocketSubscriptions() {
        // 활성 세션에서 고유 심볼 추출
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
