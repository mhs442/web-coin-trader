package com.coin.webcointrader.bybit.stream;

import com.coin.webcointrader.bybit.stream.dto.WebSocketExecutionDTO;
import com.coin.webcointrader.bybit.trade.dto.GetExecutionListResponse;
import com.coin.webcointrader.common.enums.LogMessage;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Private WebSocket 체결(execution) 대기자.
 * 주문 전에 orderLinkId로 대기를 등록하고, WS로 들어온 체결을 모아 전량 체결 시 결과를 돌려준다.
 *
 * 흐름: expect(주문 전) → 주문 → await(최대 대기 시간) → 결과 또는 null(호출부는 REST 체결 조회로 fallback)
 * - 체결 push가 REST 주문 응답보다 먼저 올 수 있어 반드시 주문 전에 등록해야 한다.
 * - 같은 execId는 한 번만 집계하고, leavesQty=0(잔량 없음) 수신 시 전량 체결로 판단한다.
 * - 집계 공식은 REST 체결 조회와 동일: execPrice = Σ(가격×수량)/Σ수량, execFee = Σ수수료, execQty = Σ수량
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BybitExecutionWaiter {

    private static final String TRADE_EXEC_TYPE = "Trade"; // 일반 주문 체결 (Funding/ADL/BustTrade 제외)
    private static final long STALE_MS = 60_000L;           // 정리되지 않은 대기 항목 만료 시간

    private final BybitPrivateWebSocketClient privateWebSocketClient;

    // orderLinkId → 대기 중인 체결
    private final Map<String, PendingFill> pending = new ConcurrentHashMap<>();

    /**
     * 빈 초기화 시 private WS 체결 콜백을 등록한다.
     */
    @PostConstruct
    public void init() {
        privateWebSocketClient.addExecutionCallback(this::onExecution);
    }

    /**
     * 주문 체결 대기를 등록한다. 주문 전송 전에 호출해야 한다.
     * 사용자의 private 스트림이 준비되지 않았으면 등록하지 않는다. (대기 없이 바로 REST 조회하도록)
     *
     * @param userId      사용자 ID
     * @param orderLinkId 주문에 지정할 orderLinkId
     * @return 등록했으면 true, 스트림 미준비 등으로 등록하지 않았으면 false
     */
    public boolean expect(Long userId, String orderLinkId) {
        purgeStale();
        if (orderLinkId == null || !privateWebSocketClient.isReady(userId)) return false;
        pending.put(orderLinkId, new PendingFill(userId, System.currentTimeMillis()));
        return true;
    }

    /**
     * 등록한 주문의 전량 체결을 최대 timeoutMs 동안 기다린다. 결과와 상관없이 대기 항목은 제거한다.
     *
     * @param orderLinkId 대기 중인 orderLinkId
     * @param timeoutMs   최대 대기 시간 (밀리초)
     * @return 집계된 체결 정보 (execPrice: 수량 가중 평균가, execFee: 수수료 합, execQty: 수량 합),
     *         미등록·시간 초과 시 null
     */
    public GetExecutionListResponse.ExecutionInfo await(String orderLinkId, long timeoutMs) {
        if (orderLinkId == null) return null;
        PendingFill fill = pending.get(orderLinkId);
        // 미등록(스트림 미준비 등) → 대기 없이 즉시 null
        if (fill == null) return null;
        try {
            return fill.future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn(LogMessage.PRIVATE_WS_EXECUTION_WAIT_TIMEOUT.getMessage(), orderLinkId, timeoutMs);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            return null;
        } finally {
            pending.remove(orderLinkId, fill);
        }
    }

    /**
     * 대기를 취소한다. (주문 실패 등으로 체결을 기다릴 필요가 없을 때)
     *
     * @param orderLinkId 취소할 orderLinkId
     */
    public void cancel(String orderLinkId) {
        if (orderLinkId != null) pending.remove(orderLinkId);
    }

    /**
     * WS 체결 수신 콜백. 대기 중인 주문의 일반 체결만 집계한다. (WebSocket 수신 스레드에서 호출)
     *
     * @param userId 체결이 발생한 연결의 사용자 ID
     * @param data   체결 건
     */
    void onExecution(Long userId, WebSocketExecutionDTO.ExecutionData data) {
        if (!TRADE_EXEC_TYPE.equals(data.getExecType()) || data.getOrderLinkId() == null) return;
        PendingFill fill = pending.get(data.getOrderLinkId());
        // 미등록 주문이거나 다른 사용자 연결에서 온 체결은 무시
        if (fill == null || !fill.userId.equals(userId)) return;
        fill.add(data);
    }

    /** 등록 후 STALE_MS가 지나도 정리되지 않은 항목을 제거한다. (await·cancel 누락 대비) */
    private void purgeStale() {
        long threshold = System.currentTimeMillis() - STALE_MS;
        pending.values().removeIf(f -> f.createdAt < threshold);
    }

    /**
     * 주문 1건의 체결 집계 상태.
     * 부분 체결이 여러 메시지로 나뉘어 올 수 있어 execId로 중복을 걸러 누적한다.
     */
    private static class PendingFill {
        private final Long userId;                  // 주문 사용자 ID
        private final long createdAt;               // 등록 시각 (만료 판단용)
        private final CompletableFuture<GetExecutionListResponse.ExecutionInfo> future =
                new CompletableFuture<>();          // 전량 체결 시 완료
        private final Set<String> execIds = new HashSet<>(); // 집계한 execId (중복 수신 방지)
        private BigDecimal totalQty = BigDecimal.ZERO;   // 체결 수량 합
        private BigDecimal totalValue = BigDecimal.ZERO; // Σ(체결가 × 수량)
        private BigDecimal totalFee = BigDecimal.ZERO;   // 수수료 합
        private WebSocketExecutionDTO.ExecutionData first; // 첫 체결 건 (symbol/orderId 등 기준)

        PendingFill(Long userId, long createdAt) {
            this.userId = userId;
            this.createdAt = createdAt;
        }

        /** 체결 건을 누적하고, 잔량이 0이면 집계 결과로 완료한다. */
        synchronized void add(WebSocketExecutionDTO.ExecutionData d) {
            if (future.isDone() || !execIds.add(d.getExecId())) return;
            if (first == null) first = d;

            BigDecimal qty = new BigDecimal(d.getExecQty());
            totalQty = totalQty.add(qty);
            totalValue = totalValue.add(new BigDecimal(d.getExecPrice()).multiply(qty));
            totalFee = totalFee.add(new BigDecimal(d.getExecFee()));

            // 잔량 0 → 전량 체결 완료
            if (d.getLeavesQty() != null && new BigDecimal(d.getLeavesQty()).signum() == 0) {
                future.complete(toExecutionInfo());
            }
        }

        private GetExecutionListResponse.ExecutionInfo toExecutionInfo() {
            // 수량 합이 0이면(비정상 데이터) 첫 체결가 사용
            BigDecimal avgPrice = totalQty.signum() > 0
                    ? totalValue.divide(totalQty, 8, RoundingMode.HALF_UP).stripTrailingZeros()
                    : new BigDecimal(first.getExecPrice());

            GetExecutionListResponse.ExecutionInfo info = new GetExecutionListResponse.ExecutionInfo();
            info.setSymbol(first.getSymbol());
            info.setOrderId(first.getOrderId());
            info.setSide(first.getSide());
            info.setOrderQty(first.getOrderQty());
            info.setExecTime(first.getExecTime());
            info.setExecPrice(avgPrice.toPlainString());
            info.setExecQty(totalQty.stripTrailingZeros().toPlainString());
            info.setExecFee(totalFee.stripTrailingZeros().toPlainString());
            return info;
        }
    }
}
