package com.coin.webcointrader.binance.stream;

import com.coin.webcointrader.binance.stream.dto.BinanceFilledOrderDTO;
import com.coin.webcointrader.binance.stream.dto.BinanceOrderTradeUpdateDTO;
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
 * Binance 개인 스트림 체결 대기자.
 * 주문 전에 clientOrderId 로 대기를 등록하고, ORDER_TRADE_UPDATE 로 들어온 체결을 모아 주문이 끝나면 결과를 돌려준다.
 *
 * 흐름: expect(주문 전) → 주문 → await(최대 대기 시간) → 결과 또는 null(호출부는 REST 체결 조회로 fallback)
 * - 체결 push 가 REST 주문 응답보다 먼저 올 수 있어 반드시 주문 전에 등록해야 한다.
 * - 같은 체결 ID(t)는 한 번만 집계하고, x=TRADE 인 이벤트만 체결로 본다.
 * - 완료 판단: X=FILLED → 전량 체결 / X=EXPIRED·CANCELED → 체결분이 있으면 그 양으로 완료, 없으면 즉시 null
 * - 수수료는 USDT·BNB 로 나눠 합산만 한다. (USDT 환산은 호출부)
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BinanceExecutionWaiter {

    private static final String TRADE_EXEC_TYPE = "TRADE";              // 체결 이벤트
    private static final String FILLED = "FILLED";                      // 전량 체결
    private static final Set<String> ENDED_STATUSES =
            Set.of("EXPIRED", "CANCELED", "EXPIRED_IN_MATCH");          // 잔량 남기고 끝난 상태 (시장가 유동성 부족·자기체결 방지 등)
    private static final String USDT = "USDT";
    private static final String BNB = "BNB";
    private static final long STALE_MS = 60_000L;                       // 정리되지 않은 대기 항목 만료 시간

    private final BinancePrivateWebSocketClient privateWebSocketClient;

    // clientOrderId → 대기 중인 체결
    private final Map<String, PendingFill> pending = new ConcurrentHashMap<>();

    /**
     * 빈 초기화 시 개인 스트림 주문 업데이트 콜백을 등록한다.
     */
    @PostConstruct
    public void init() {
        privateWebSocketClient.addOrderUpdateCallback(this::onOrderUpdate);
    }

    /**
     * 주문 체결 대기를 등록한다. 주문 전송 전에 호출해야 한다.
     * 사용자의 개인 스트림이 준비되지 않았으면 등록하지 않는다. (대기 없이 바로 REST 조회하도록)
     *
     * @param userId        사용자 ID
     * @param clientOrderId 주문에 지정할 clientOrderId (newClientOrderId)
     * @return 등록했으면 true, 스트림 미준비 등으로 등록하지 않았으면 false
     */
    public boolean expect(Long userId, String clientOrderId) {
        purgeStale();
        if (clientOrderId == null || !privateWebSocketClient.isReady(userId)) return false;
        pending.put(clientOrderId, new PendingFill(userId, System.currentTimeMillis()));
        return true;
    }

    /**
     * 등록한 주문이 끝날 때까지 최대 timeoutMs 동안 기다린다. 결과와 상관없이 대기 항목은 제거한다.
     *
     * @param clientOrderId 대기 중인 clientOrderId
     * @param timeoutMs     최대 대기 시간 (밀리초)
     * @return 집계된 체결 결과 (avgPrice: 수량 가중 평균가, filledQty: 수량 합, usdtFee/bnbFee: 자산별 수수료 합),
     *         미등록·시간 초과·체결 없이 종료 시 null
     */
    public BinanceFilledOrderDTO await(String clientOrderId, long timeoutMs) {
        if (clientOrderId == null) return null;
        PendingFill fill = pending.get(clientOrderId);
        // 미등록(스트림 미준비 등) → 대기 없이 즉시 null
        if (fill == null) return null;
        try {
            return fill.future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn(LogMessage.BINANCE_PRIVATE_WS_EXECUTION_WAIT_TIMEOUT.getMessage(), clientOrderId, timeoutMs);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            return null;
        } finally {
            pending.remove(clientOrderId, fill);
        }
    }

    /**
     * 대기를 취소한다. (주문 실패 등으로 체결을 기다릴 필요가 없을 때)
     *
     * @param clientOrderId 취소할 clientOrderId
     */
    public void cancel(String clientOrderId) {
        if (clientOrderId != null) pending.remove(clientOrderId);
    }

    /**
     * 주문 업데이트 수신 콜백. 대기 중인 주문의 이벤트만 처리한다. (WebSocket 수신 스레드에서 호출)
     * 체결 이벤트가 아니어도 종료 상태(EXPIRED 등)는 완료 판단에 필요하므로 함께 받는다.
     *
     * @param userId 이벤트가 온 연결의 사용자 ID
     * @param order  주문 업데이트
     */
    void onOrderUpdate(Long userId, BinanceOrderTradeUpdateDTO.OrderUpdate order) {
        if (order.getClientOrderId() == null) return;
        PendingFill fill = pending.get(order.getClientOrderId());
        // 미등록 주문이거나 다른 사용자 연결에서 온 이벤트는 무시
        if (fill == null || !fill.userId.equals(userId)) return;
        fill.apply(order);
    }

    /** 등록 후 STALE_MS가 지나도 정리되지 않은 항목을 제거한다. (await·cancel 누락 대비) */
    private void purgeStale() {
        long threshold = System.currentTimeMillis() - STALE_MS;
        pending.values().removeIf(f -> f.createdAt < threshold);
    }

    /**
     * 주문 1건의 체결 집계 상태.
     * 부분 체결이 여러 이벤트로 나뉘어 와서 체결 ID로 중복을 걸러 누적한다.
     */
    private static class PendingFill {
        private final Long userId;                  // 주문 사용자 ID
        private final long createdAt;               // 등록 시각 (만료 판단용)
        private final CompletableFuture<BinanceFilledOrderDTO> future =
                new CompletableFuture<>();          // 주문 종료 시 완료 (체결 없이 종료면 null)
        private final Set<String> tradeIds = new HashSet<>(); // 집계한 체결 ID (중복 수신 방지)
        private BigDecimal totalQty = BigDecimal.ZERO;   // 체결 수량 합
        private BigDecimal totalValue = BigDecimal.ZERO; // Σ(체결가 × 수량)
        private BigDecimal usdtFee = BigDecimal.ZERO;    // USDT 수수료 합
        private BigDecimal bnbFee = BigDecimal.ZERO;     // BNB 수수료 합
        private boolean otherFeeAsset;                   // 그 외 자산 수수료 존재 여부
        private BinanceOrderTradeUpdateDTO.OrderUpdate first; // 첫 체결 건 (symbol/orderId 등 기준)

        PendingFill(Long userId, long createdAt) {
            this.userId = userId;
            this.createdAt = createdAt;
        }

        /** 체결 이벤트는 누적하고, 주문 상태가 종료면 결과로 완료한다. */
        synchronized void apply(BinanceOrderTradeUpdateDTO.OrderUpdate o) {
            if (future.isDone()) return;

            // 체결 건 누적 (같은 체결 ID 재수신은 건너뜀)
            if (TRADE_EXEC_TYPE.equals(o.getExecType()) && o.getTradeId() != null && tradeIds.add(o.getTradeId())) {
                addFill(o);
            }

            String status = o.getOrderStatus();
            if (FILLED.equals(status)) {
                future.complete(toResult());
            } else if (ENDED_STATUSES.contains(status)) {
                // 잔량을 남기고 끝남 → 체결분이 있으면 그 양으로, 없으면 null(호출부가 REST 로 확인)
                if (totalQty.signum() > 0) {
                    future.complete(toResult());
                } else {
                    log.warn(LogMessage.BINANCE_PRIVATE_WS_ORDER_ENDED_WITHOUT_FILL.getMessage(),
                            o.getClientOrderId(), status);
                    future.complete(null);
                }
            }
        }

        private void addFill(BinanceOrderTradeUpdateDTO.OrderUpdate o) {
            if (first == null) first = o;

            BigDecimal qty = new BigDecimal(o.getLastFilledQty());
            totalQty = totalQty.add(qty);
            totalValue = totalValue.add(new BigDecimal(o.getLastFilledPrice()).multiply(qty));

            // 수수료 자산별 합산 (수수료 없는 체결은 n 이 비어 옴)
            if (o.getCommission() == null || o.getCommission().isBlank()) return;
            BigDecimal fee = new BigDecimal(o.getCommission());
            if (USDT.equals(o.getCommissionAsset())) {
                usdtFee = usdtFee.add(fee);
            } else if (BNB.equals(o.getCommissionAsset())) {
                bnbFee = bnbFee.add(fee);
            } else {
                otherFeeAsset = true;
            }
        }

        private BinanceFilledOrderDTO toResult() {
            BigDecimal avgPrice = totalValue.divide(totalQty, 8, RoundingMode.HALF_UP).stripTrailingZeros();
            return BinanceFilledOrderDTO.builder()
                    .symbol(first.getSymbol())
                    .orderId(first.getOrderId())
                    .side(first.getSide())
                    .orderQty(first.getOrigQty())
                    .avgPrice(avgPrice.toPlainString())
                    .filledQty(totalQty.stripTrailingZeros().toPlainString())
                    .usdtFee(usdtFee.stripTrailingZeros().toPlainString())
                    .bnbFee(bnbFee.stripTrailingZeros().toPlainString())
                    .otherFeeAsset(otherFeeAsset)
                    .execTime(String.valueOf(first.getTradeTime()))
                    .build();
        }
    }
}
