package com.coin.webcointrader.common.autotrade.service;

import com.coin.webcointrader.common.ExchangeClientRegistry;
import com.coin.webcointrader.common.autotrade.util.VolumeWindow;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.market.client.ExchangeMarketClient;
import com.coin.webcointrader.common.market.dto.PublicTradeDTO;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 거래량 급변 감시용 심볼별 최근 60초 체결량 집계.
 * 감시를 켠 큐가 활성인 (거래소, 심볼)만 체결 스트림을 구독하고, 구독 시작 시각부터 집계한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VolumeMonitorService {

    private final ExchangeClientRegistry adapterRegistry;
    private final Clock clock;

    // 집계 창 (키: "exchangeType:symbol")
    private final Map<String, VolumeWindow> windows = new ConcurrentHashMap<>();

    /**
     * 모든 거래소 시세 클라이언트에 체결 리스너를 등록한다.
     */
    @PostConstruct
    public void init() {
        for (ExchangeMarketClient market : adapterRegistry.markets()) {
            ExchangeType exchangeType = market.getExchangeType();
            market.addTradeListener(trade -> onTrade(exchangeType, trade));
        }
    }

    /**
     * 감시 대상 심볼로 체결 구독과 집계 창을 맞춘다.
     * 새 심볼은 지금부터 집계를 시작하고(워밍업 60초), 빠진 심볼은 집계를 버린다. 이미 감시 중인 심볼은 이어간다.
     *
     * @param symbolsByExchange 거래소별 감시 심볼 (없는 거래소는 전부 해지)
     */
    public synchronized void syncSubscriptions(Map<ExchangeType, Set<String>> symbolsByExchange) {
        long now = clock.millis();
        for (ExchangeMarketClient market : adapterRegistry.markets()) {
            ExchangeType exchangeType = market.getExchangeType();
            Set<String> symbols = symbolsByExchange.getOrDefault(exchangeType, Set.of());
            // 새 심볼 집계 시작
            for (String symbol : symbols) {
                windows.computeIfAbsent(key(exchangeType, symbol), k -> new VolumeWindow(now));
            }
            // 빠진 심볼 집계 제거
            String prefix = exchangeType.name() + ":";
            windows.keySet().removeIf(k -> k.startsWith(prefix) && !symbols.contains(k.substring(prefix.length())));
            market.syncTradeSubscriptions(symbols);
        }
    }

    /**
     * 최근 60초 체결량을 반환한다.
     *
     * @param exchangeType 거래소
     * @param symbol       심볼
     * @return 체결량 합 (코인 기준), 감시 대상이 아니거나 구독 후 60초가 안 됐으면(워밍업) null
     */
    public Double getVolume(ExchangeType exchangeType, String symbol) {
        VolumeWindow window = windows.get(key(exchangeType, symbol));
        long now = clock.millis();
        if (window == null || !window.isFull(now)) {
            return null;
        }
        return window.sum(now);
    }

    /** 체결 1건을 해당 심볼 집계 창에 반영한다. (WebSocket 수신 스레드) */
    private void onTrade(ExchangeType exchangeType, PublicTradeDTO trade) {
        VolumeWindow window = windows.get(key(exchangeType, trade.getSymbol()));
        if (window == null) {
            return;
        }
        try {
            window.add(trade.getTradeTime(), Double.parseDouble(trade.getQty()));
        } catch (NumberFormatException e) {
            log.warn("[거래량 집계] 수량 파싱 실패: {} {} qty={}", exchangeType, trade.getSymbol(), trade.getQty());
        }
    }

    private static String key(ExchangeType exchangeType, String symbol) {
        return exchangeType.name() + ":" + symbol;
    }
}
