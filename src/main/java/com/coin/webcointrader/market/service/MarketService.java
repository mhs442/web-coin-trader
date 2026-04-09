package com.coin.webcointrader.market.service;

import com.coin.webcointrader.common.client.market.BybitWebSocketClient;
import com.coin.webcointrader.common.client.market.MarketClient;
import com.coin.webcointrader.common.client.market.dto.WebSocketTickerDTO;
import com.coin.webcointrader.common.dto.response.FindTickerResponse;
import com.coin.webcointrader.common.dto.response.GetInstrumentsInfoResponse;
import com.coin.webcointrader.common.dto.response.GetKlineResponse;
import com.coin.webcointrader.common.dto.response.OrderBookResponse;
import com.coin.webcointrader.common.enums.Category;
import com.coin.webcointrader.common.enums.LogMessage;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * Bybit 시세 데이터 서비스.
 * 1초마다 REST 캐시를 갱신하여 대시보드에 최신 시세를 제공하고,
 * WebSocket으로 수신된 실시간 데이터를 자동매매 엔진에 전달한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarketService {

    private final MarketClient marketClient;
    private final BybitWebSocketClient bybitWebSocketClient;

    /**
     * 애플리케이션 시작 시 WebSocket 티커 콜백을 등록한다.
     */
    @PostConstruct
    public void init() {
        bybitWebSocketClient.setTickerCallback(this::onTickerUpdate);
        log.info(LogMessage.WS_TICKER_CALLBACK_REGISTERED.getMessage());
        refreshQtyStepCache();
    }

    // REST 폴링 캐시 (대시보드용)
    private final AtomicReference<FindTickerResponse> cachedTickers = new AtomicReference<>();

    // WebSocket 실시간 데이터 저장 (심볼 → 티커 정보)
    private final ConcurrentHashMap<String, FindTickerResponse.TickerInfo> wsTickerMap = new ConcurrentHashMap<>();

    // 가격 변동 리스너 목록 (BiConsumer<심볼, 현재가>)
    private final List<BiConsumer<String, String>> priceListeners = new CopyOnWriteArrayList<>();

    // 종목별 qtyStep 캐시 (심볼 → qtyStep, 예: "BTCUSDT" → "0.001")
    private final ConcurrentHashMap<String, String> qtyStepCache = new ConcurrentHashMap<>();

    /**
     * 1초마다 Bybit API를 호출하여 Ticker 정보를 가져와 내부 캐시를 업데이트합니다.
     * USDT 마켓 종목만 필터링하고, 24시간 거래량 기준 내림차순으로 정렬합니다.
     */
    @Scheduled(fixedRate = 1000)
    public void refreshTickersCache() {
        FindTickerResponse response = marketClient.getTickers(Category.LINEAR.getCategory()).getBody();

        if (response != null && response.getResult() != null && response.getResult().getList() != null) {
            response.getResult().getList()
                    .removeIf(ticker -> !ticker.getSymbol().endsWith("USDT"));
            response.getResult().getList()
                    // 24시간 거래대금(turnover) 내림차순 정렬 (Bybit 마켓 페이지 기준)
                    .sort((t1, t2) -> Double.compare(Double.parseDouble(t2.getTurnover24h()), Double.parseDouble(t1.getTurnover24h())));
            cachedTickers.set(response);
        }
    }

    /**
     * 캐시된 티커 목록을 반환한다.
     * 캐시가 비어있으면 즉시 갱신 후 반환한다.
     *
     * @return USDT 선물 전체 티커 목록 응답 (24시간 거래량 내림차순 정렬)
     */
    public FindTickerResponse getTickers() {
        if (cachedTickers.get() == null) {
            refreshTickersCache();
        }
        return cachedTickers.get();
    }

    /**
     * WebSocket에서 수신한 티커 데이터를 처리한다.
     * snapshot이면 전체 데이터를 교체하고, delta이면 변경된 필드만 병합한다.
     * 가격 변동이 발생하면 등록된 리스너에 알린다.
     *
     * @param dto WebSocket 티커 메시지 DTO
     */
    public void onTickerUpdate(WebSocketTickerDTO dto) {
        if (dto == null || dto.getData() == null) {
            return;
        }

        WebSocketTickerDTO.TickerData data = dto.getData();
        String symbol = data.getSymbol();
        if (symbol == null) {
            return;
        }

        if ("snapshot".equals(dto.getType())) {
            // snapshot: 전체 데이터 교체
            FindTickerResponse.TickerInfo tickerInfo = toTickerInfo(data);
            wsTickerMap.put(symbol, tickerInfo);
        } else if ("delta".equals(dto.getType())) {
            // delta: 기존 데이터에 변경 필드만 병합
            wsTickerMap.compute(symbol, (key, existing) -> {
                if (existing == null) {
                    // 기존 데이터가 없으면 새로 생성
                    return toTickerInfo(data);
                }
                // 변경된 필드만 업데이트
                mergeDelta(existing, data);
                return existing;
            });
        }

        // 가격 변동 리스너 알림
        if (data.getLastPrice() != null) {
            notifyPriceListeners(symbol, data.getLastPrice());
        }
    }

    /**
     * 가격 변동 리스너를 등록한다.
     * WebSocket에서 가격이 수신될 때마다 콜백이 호출된다.
     *
     * @param listener BiConsumer(심볼, 현재가) 콜백
     */
    public void addPriceListener(BiConsumer<String, String> listener) {
        priceListeners.add(listener);
    }

    /**
     * WebSocket으로 수신된 특정 심볼의 실시간 티커 정보를 반환한다.
     *
     * @param symbol 종목 심볼 (예: "BTCUSDT")
     * @return 티커 정보, 데이터가 없으면 null
     */
    public FindTickerResponse.TickerInfo getWsTicker(String symbol) {
        return wsTickerMap.get(symbol);
    }

    /**
     * 특정 종목의 K-라인(캔들) 데이터를 조회한다.
     *
     * @param symbol   종목 심볼 (예: "BTCUSDT")
     * @param interval 캔들 인터벌 (예: "1", "5", "15", "60", "D")
     * @return 최근 200개 캔들 데이터 응답
     */
    public GetKlineResponse getKline(String symbol, String interval) {
        return marketClient.getKline(Category.LINEAR.getCategory(), symbol, interval, 200).getBody();
    }

    /**
     * 특정 종목의 호가창(오더북) 데이터를 조회한다.
     *
     * @param symbol 종목 심볼 (예: "BTCUSDT")
     * @return 매수·매도 각 상위 50개 호가 응답
     */
    public OrderBookResponse getOrderBook(String symbol) {
        return marketClient.getOrderBook(Category.LINEAR.getCategory(), symbol, 50).getBody();
    }

    /**
     * Bybit에서 전체 Linear 종목의 qtyStep을 조회하여 캐시에 저장한다.
     * 애플리케이션 시작 시 호출되며, 캐시가 비어있을 때도 호출된다.
     */
    public void refreshQtyStepCache() {
        try {
            GetInstrumentsInfoResponse response = marketClient.getInstrumentsInfo(
                    Category.LINEAR.getCategory()).getBody();

            if (response != null && response.getResult() != null
                    && response.getResult().getList() != null) {
                for (GetInstrumentsInfoResponse.InstrumentInfo info : response.getResult().getList()) {
                    if (info.getSymbol() != null && info.getLotSizeFilter() != null
                            && info.getLotSizeFilter().getQtyStep() != null) {
                        qtyStepCache.put(info.getSymbol(), info.getLotSizeFilter().getQtyStep());
                    }
                }
                log.info(LogMessage.QTY_STEP_CACHE_INIT_SUCCESS.getMessage(), qtyStepCache.size());
            }
        } catch (Exception e) {
            log.error(LogMessage.QTY_STEP_CACHE_INIT_FAILED.getMessage(), e.getMessage());
        }
    }

    /**
     * 특정 심볼의 qtyStep을 반환한다.
     * 캐시에 없으면 갱신을 시도한다.
     *
     * @param symbol 종목 심볼 (예: "BTCUSDT")
     * @return qtyStep 문자열 (예: "0.001"), 조회 실패 시 null
     */
    public String getQtyStep(String symbol) {
        String qtyStep = qtyStepCache.get(symbol);
        // 캐시 미스 시 갱신 시도
        if (qtyStep == null) {
            refreshQtyStepCache();
            qtyStep = qtyStepCache.get(symbol);
        }
        return qtyStep;
    }

    /**
     * USDT 금액을 코인 수량으로 변환한다.
     * qtyStep 단위에 맞춰 내림(floor) 처리한다.
     *
     * <p>계산식: qty = floor(usdtAmount / currentPrice / qtyStep) * qtyStep</p>
     *
     * @param symbol       종목 심볼 (예: "BTCUSDT")
     * @param usdtAmount   USDT 금액 (예: 15)
     * @param currentPrice 현재가 (예: 87000.5)
     * @return 코인 수량 문자열 (예: "0.001"), 변환 실패 시 null
     */
    public String convertUsdtToQty(String symbol, BigDecimal usdtAmount, BigDecimal currentPrice) {
        String qtyStepStr = getQtyStep(symbol);
        if (qtyStepStr == null) {
            log.error(LogMessage.QTY_STEP_LOOKUP_FAILED.getMessage(), symbol);
            return null;
        }

        BigDecimal qtyStep = new BigDecimal(qtyStepStr);
        // 소수점 자릿수 계산 (예: "0.001" → scale=3)
        int scale = qtyStep.stripTrailingZeros().scale();
        if (scale < 0) scale = 0;

        // qty = floor(usdtAmount / currentPrice / qtyStep) * qtyStep
        BigDecimal rawQty = usdtAmount.divide(currentPrice, scale + 4, RoundingMode.HALF_UP);
        BigDecimal steps = rawQty.divide(qtyStep, 0, RoundingMode.DOWN); // 내림
        BigDecimal qty = steps.multiply(qtyStep);

        // 최소 수량 체크 (0 이하면 주문 불가)
        if (qty.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn(LogMessage.QTY_CONVERT_ZERO.getMessage(),
                    symbol, usdtAmount, currentPrice, qtyStep);
            return null;
        }

        return qty.stripTrailingZeros().toPlainString();
    }

    // ─────────────────────────────────────────────
    // 내부 헬퍼 메서드
    // ─────────────────────────────────────────────

    /**
     * WebSocket 티커 데이터를 FindTickerResponse.TickerInfo로 변환한다.
     */
    private FindTickerResponse.TickerInfo toTickerInfo(WebSocketTickerDTO.TickerData data) {
        FindTickerResponse.TickerInfo info = new FindTickerResponse.TickerInfo();
        info.setSymbol(data.getSymbol());
        info.setLastPrice(data.getLastPrice());
        info.setPrice24hPcnt(data.getPrice24hPcnt());
        info.setHighPrice24h(data.getHighPrice24h());
        info.setLowPrice24h(data.getLowPrice24h());
        info.setVolume24h(data.getVolume24h());
        info.setTurnover24h(data.getTurnover24h());
        return info;
    }

    /**
     * delta 데이터의 non-null 필드를 기존 티커 정보에 병합한다.
     */
    private void mergeDelta(FindTickerResponse.TickerInfo existing, WebSocketTickerDTO.TickerData delta) {
        if (delta.getLastPrice() != null) {
            existing.setLastPrice(delta.getLastPrice());
        }
        if (delta.getPrice24hPcnt() != null) {
            existing.setPrice24hPcnt(delta.getPrice24hPcnt());
        }
        if (delta.getHighPrice24h() != null) {
            existing.setHighPrice24h(delta.getHighPrice24h());
        }
        if (delta.getLowPrice24h() != null) {
            existing.setLowPrice24h(delta.getLowPrice24h());
        }
        if (delta.getVolume24h() != null) {
            existing.setVolume24h(delta.getVolume24h());
        }
        if (delta.getTurnover24h() != null) {
            existing.setTurnover24h(delta.getTurnover24h());
        }
    }

    /**
     * 등록된 가격 리스너에 가격 변동을 알린다.
     */
    private void notifyPriceListeners(String symbol, String price) {
        for (BiConsumer<String, String> listener : priceListeners) {
            try {
                listener.accept(symbol, price);
            } catch (Exception e) {
                log.error(LogMessage.PRICE_LISTENER_ERROR.getMessage(), symbol, e.getMessage());
            }
        }
    }
}
