package com.coin.webcointrader.binance.market;

import com.coin.webcointrader.binance.common.BinanceApiException;
import com.coin.webcointrader.binance.market.dto.BinanceDepthResponse;
import com.coin.webcointrader.binance.market.dto.BinanceExchangeInfoResponse;
import com.coin.webcointrader.binance.market.dto.BinancePriceTickerResponse;
import com.coin.webcointrader.binance.market.dto.BinanceWsAggTradeDTO;
import com.coin.webcointrader.binance.market.dto.BinanceWsKlineDTO;
import com.coin.webcointrader.binance.market.dto.BinanceWsTickerDTO;
import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.market.client.AbstractExchangeMarketClient;
import com.coin.webcointrader.common.market.dto.InstrumentDTO;
import com.coin.webcointrader.common.market.dto.KlineDTO;
import com.coin.webcointrader.common.market.dto.PublicTradeDTO;
import com.coin.webcointrader.common.market.dto.OrderBookDTO;
import com.coin.webcointrader.common.market.dto.TickerDTO;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Binance USDⓈ-M 선물 시세 클라이언트.
 * 대시보드 목록은 Bybit 목록을 쓰므로(MarketService.getDisplayTickers) 여기서는 24시간 통계 목록을 조회하지 않고,
 * 거래에 필요한 거래 규칙·캔들·호가·실시간 시세만 제공한다.
 */
@Component
public class BinanceMarketClient extends AbstractExchangeMarketClient {

    // 차트 화면이 캔들·호가를 1초마다 요청하므로, 같은 요청은 1초 동안 응답을 공유해 탭 수만큼 가중치가 늘지 않게 한다
    private static final long RESPONSE_CACHE_TTL_MS = 1_000L;

    // 호가 조회에 허용되는 단계 수 (오름차순)
    private static final int[] DEPTH_LIMITS = {5, 10, 20, 50, 100, 500, 1000};

    // 공통 봉 간격(분 단위 숫자, 일·주·월 D·W·M) → Binance 간격
    private static final Map<String, String> INTERVALS = Map.ofEntries(
            Map.entry("1", "1m"), Map.entry("3", "3m"), Map.entry("5", "5m"),
            Map.entry("15", "15m"), Map.entry("30", "30m"), Map.entry("60", "1h"),
            Map.entry("120", "2h"), Map.entry("240", "4h"), Map.entry("360", "6h"),
            Map.entry("720", "12h"), Map.entry("D", "1d"), Map.entry("W", "1w"), Map.entry("M", "1M"));

    private final BinanceMarketFeignClient feignClient;
    private final BinanceWebSocketClient webSocketClient;

    // 요청별 최근 응답 (키: 요청 종류·파라미터)
    private final Map<String, CachedResponse> responseCache = new ConcurrentHashMap<>();

    /** 캐시된 응답과 조회 시각 */
    private record CachedResponse(long fetchedAtMs, Object value) {}

    public BinanceMarketClient(BinanceMarketFeignClient feignClient, BinanceWebSocketClient webSocketClient, Clock clock) {
        super(clock);
        this.feignClient = feignClient;
        this.webSocketClient = webSocketClient;
    }

    /**
     * 빈 초기화 시 공개 WebSocket의 ticker/kline 수신 콜백을 등록하고 거래 규칙 캐시를 채운다.
     */
    @PostConstruct
    public void init() {
        webSocketClient.addTickerCallback(this::onTickerReceived);
        webSocketClient.addKlineCallback(this::onKlineReceived);
        webSocketClient.addTradeCallback(this::onTradeReceived);
        refreshInstruments();
    }

    @Override
    public ExchangeType getExchangeType() {
        return ExchangeType.BINANCE;
    }

    // ─── REST ─────────────────────────────────────────

    /**
     * Binance GET /fapi/v2/ticker/price 전체 종목 최근 체결가를 조회한다. (자동매매 WS 장애 시 fallback 가격용)
     *
     * @return 심볼·현재가만 채운 티커 목록 (응답이 없으면 빈 목록)
     */
    @Override
    protected List<TickerDTO> fetchTickers() {
        List<BinancePriceTickerResponse> response = feignClient.getPriceTickers();
        if (response == null) {
            return List.of();
        }
        return response.stream()
                .filter(t -> t.getSymbol() != null && t.getPrice() != null)
                .<TickerDTO>map(t -> TickerDTO.builder().symbol(t.getSymbol()).lastPrice(t.getPrice()).build())
                .toList();
    }

    /**
     * Binance GET /fapi/v1/exchangeInfo 전체 종목 거래 규칙을 조회한다.
     *
     * @return USDT 무기한·거래 중 종목의 수량 단위·가격 단위·최소 주문 수량
     */
    @Override
    protected List<InstrumentDTO> fetchInstruments() {
        BinanceExchangeInfoResponse response = feignClient.getExchangeInfo();
        if (response == null || response.getSymbols() == null) {
            return List.of();
        }

        List<InstrumentDTO> result = new ArrayList<>();
        for (BinanceExchangeInfoResponse.SymbolInfo info : response.getSymbols()) {
            // USDT 무기한·거래 중만 (분기물·USDC 마진·정산/상장 대기 종목 제외)
            if (!"PERPETUAL".equals(info.getContractType()) || !"USDT".equals(info.getQuoteAsset())
                    || !"TRADING".equals(info.getStatus())) {
                continue;
            }
            // 주문은 시장가라 MARKET_LOT_SIZE 우선, 없으면 LOT_SIZE
            BinanceExchangeInfoResponse.Filter lot = findFilter(info, "MARKET_LOT_SIZE");
            if (lot == null || lot.getStepSize() == null) {
                lot = findFilter(info, "LOT_SIZE");
            }
            // 수량 단위가 없으면 주문 수량 계산 불가 → 제외
            if (info.getSymbol() == null || lot == null || lot.getStepSize() == null) {
                continue;
            }
            BinanceExchangeInfoResponse.Filter price = findFilter(info, "PRICE_FILTER");
            result.add(InstrumentDTO.builder()
                    .symbol(info.getSymbol())
                    .qtyStep(lot.getStepSize())
                    .minOrderQty(lot.getMinQty())
                    .tickSize(price != null ? price.getTickSize() : null)
                    .build());
        }
        return result;
    }

    /**
     * Binance GET /fapi/v1/klines 캔들을 조회한다. 같은 요청은 1초 동안 캐시한다.
     *
     * @param symbol   종목 심볼 (예: "BTCUSDT")
     * @param interval 봉 간격 (분 단위 숫자 "1"·"60" 등, 일봉 "D")
     * @param limit    조회 건수
     * @return 시작 시각 오름차순 캔들 목록
     * @throws CustomException 지원하지 않는 간격이거나 Binance 오류 응답인 경우 (MARKET_DATA_FAILED)
     */
    @Override
    public List<KlineDTO> getKlines(String symbol, String interval, int limit) {
        String binanceInterval = INTERVALS.get(interval);
        if (binanceInterval == null) {
            throw new CustomException(ExceptionMessage.MARKET_DATA_FAILED, "지원하지 않는 봉 간격: " + interval);
        }

        return cached("klines:" + symbol + ":" + binanceInterval + ":" + limit, () -> {
            List<List<String>> rows = callApi(() -> feignClient.getKlines(symbol, binanceInterval, limit));
            // Binance 응답은 이미 오름차순 [시작시각, 시가, 고가, 저가, 종가, 거래량, 종료시각, ...]
            List<KlineDTO> klines = new ArrayList<>();
            for (List<String> row : rows) {
                klines.add(KlineDTO.builder()
                        .symbol(symbol)
                        .startTime(Long.parseLong(row.get(0)))
                        .open(row.get(1))
                        .high(row.get(2))
                        .low(row.get(3))
                        .close(row.get(4))
                        .volume(row.get(5))
                        .endTime(Long.parseLong(row.get(6)))
                        .build());
            }
            return klines;
        });
    }

    /**
     * Binance GET /fapi/v1/depth 호가창을 조회한다. 같은 요청은 1초 동안 캐시한다.
     * 허용 단계 수(5·10·20·50·100·500·1000)가 아니면 그보다 큰 허용값으로 조회한 뒤 요청 단계 수만큼 자른다.
     *
     * @param symbol 종목 심볼 (예: "BTCUSDT")
     * @param depth  호가 단계 수
     * @return 매도(asks, 가격 오름차순)·매수(bids, 가격 내림차순) 호가
     * @throws CustomException Binance 오류 응답이거나 응답이 없는 경우 (MARKET_DATA_FAILED)
     */
    @Override
    public OrderBookDTO getOrderBook(String symbol, int depth) {
        int limit = depthLimitFor(depth);
        return cached("depth:" + symbol + ":" + depth, () -> {
            BinanceDepthResponse response = callApi(() -> feignClient.getDepth(symbol, limit));
            if (response == null) {
                throw new CustomException(ExceptionMessage.MARKET_DATA_FAILED, "호가 응답 없음");
            }
            return OrderBookDTO.builder()
                    .symbol(symbol)
                    .asks(toLevels(response.getAsks(), depth))
                    .bids(toLevels(response.getBids(), depth))
                    .timestamp(response.getTransactionTime() != null ? response.getTransactionTime() : 0L)
                    .build();
        });
    }

    // ─── WebSocket ────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    public void syncSubscriptions(Set<String> symbols) {
        webSocketClient.syncSubscriptions(symbols);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void syncTradeSubscriptions(Set<String> symbols) {
        webSocketClient.syncTradeSubscriptions(symbols);
    }

    /**
     * 공개 WebSocket aggTrade 메시지 수신 시 호출된다. 공통 체결로 바꿔 리스너에 넘긴다.
     *
     * @param dto WebSocket 집계 체결 메시지
     */
    private void onTradeReceived(BinanceWsAggTradeDTO dto) {
        if (dto == null || dto.getSymbol() == null || dto.getQty() == null) return;
        publishTrade(PublicTradeDTO.builder()
                .symbol(dto.getSymbol())
                .tradeTime(dto.getTradeTime())
                .qty(dto.getQty())
                .build());
    }

    /**
     * 공개 WebSocket ticker 메시지 수신 시 호출된다.
     * 매번 전체 값이 오므로 그대로 교체하고, 퍼센트 변동률은 공통 형식(비율)으로 바꾼다.
     *
     * @param dto WebSocket 티커 메시지
     */
    private void onTickerReceived(BinanceWsTickerDTO dto) {
        if (dto == null || dto.getSymbol() == null) return;
        publishTicker(TickerDTO.builder()
                .symbol(dto.getSymbol())
                .lastPrice(dto.getLastPrice())
                .price24hPcnt(percentToRatio(dto.getPriceChangePercent()))
                .highPrice24h(dto.getHighPrice())
                .lowPrice24h(dto.getLowPrice())
                .volume24h(dto.getVolume())
                .turnover24h(dto.getQuoteVolume())
                .build());
    }

    /**
     * 공개 WebSocket kline 메시지 수신 시 호출된다. (마감 여부 필터는 공통 처리에서 수행)
     *
     * @param dto WebSocket kline 메시지
     */
    private void onKlineReceived(BinanceWsKlineDTO dto) {
        if (dto == null || dto.getSymbol() == null || dto.getKline() == null) return;
        BinanceWsKlineDTO.Kline k = dto.getKline();
        publishKline(KlineDTO.builder()
                .symbol(dto.getSymbol())
                .startTime(k.getStartTime())
                .endTime(k.getCloseTime())
                .open(k.getOpen())
                .high(k.getHigh())
                .low(k.getLow())
                .close(k.getClose())
                .volume(k.getVolume())
                .confirmed(k.isClosed())
                .build());
    }

    // ─── 내부 헬퍼 ────────────────────────────────────

    /**
     * 같은 키의 응답이 1초 이내에 조회됐으면 재사용하고, 아니면 새로 조회해 저장한다.
     */
    @SuppressWarnings("unchecked")
    private <T> T cached(String key, Supplier<T> loader) {
        long now = clock.millis();
        CachedResponse cachedResponse = responseCache.get(key);
        if (cachedResponse != null && now - cachedResponse.fetchedAtMs() < RESPONSE_CACHE_TTL_MS) {
            return (T) cachedResponse.value();
        }
        T value = loader.get();
        responseCache.put(key, new CachedResponse(now, value));
        return value;
    }

    /**
     * Binance 오류 응답 예외를 시세 조회 실패 예외("[code] msg")로 바꾼다.
     */
    private static <T> T callApi(Supplier<T> call) {
        try {
            return call.get();
        } catch (BinanceApiException e) {
            throw new CustomException(ExceptionMessage.MARKET_DATA_FAILED, e.getMessage());
        }
    }

    /** 요청 단계 수 이상인 가장 작은 허용값 (최대 1000) */
    private static int depthLimitFor(int depth) {
        for (int limit : DEPTH_LIMITS) {
            if (limit >= depth) return limit;
        }
        return DEPTH_LIMITS[DEPTH_LIMITS.length - 1];
    }

    private static List<OrderBookDTO.Level> toLevels(List<String[]> rows, int depth) {
        if (rows == null) return List.of();
        return rows.stream()
                .limit(depth)
                .map(r -> new OrderBookDTO.Level(r[0], r[1]))
                .toList();
    }

    private static BinanceExchangeInfoResponse.Filter findFilter(BinanceExchangeInfoResponse.SymbolInfo info, String type) {
        if (info.getFilters() == null) return null;
        return info.getFilters().stream()
                .filter(f -> type.equals(f.getFilterType()))
                .findFirst()
                .orElse(null);
    }

    /** "1.230"(%) → "0.0123" (null이면 null) */
    private static String percentToRatio(String percent) {
        if (percent == null) return null;
        return new BigDecimal(percent).movePointLeft(2).stripTrailingZeros().toPlainString();
    }
}
