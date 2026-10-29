package com.coin.webcointrader.bybit.market;

import com.coin.webcointrader.bybit.market.dto.WebSocketKlineDTO;
import com.coin.webcointrader.bybit.market.dto.WebSocketPublicTradeDTO;
import com.coin.webcointrader.bybit.market.dto.WebSocketTickerDTO;
import com.coin.webcointrader.bybit.common.BybitMasterDTO;
import com.coin.webcointrader.bybit.market.dto.FindTickerResponse;
import com.coin.webcointrader.bybit.market.dto.GetInstrumentsInfoResponse;
import com.coin.webcointrader.bybit.market.dto.GetKlineResponse;
import com.coin.webcointrader.bybit.market.dto.OrderBookResponse;
import com.coin.webcointrader.common.enums.Category;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Bybit 시세 클라이언트.
 * Bybit 공개 REST(티커·캔들·호가·거래 규칙)와 공개 WebSocket(ticker·kline.1) 응답을 공통 DTO로 변환한다.
 */
@Slf4j
@Component
public class BybitMarketClient extends AbstractExchangeMarketClient {

    // 거래 규칙 페이지 크기 (Bybit 최대값)
    private static final int INSTRUMENTS_PAGE_LIMIT = 1000;

    private final MarketClient marketClient;
    private final BybitWebSocketClient bybitWebSocketClient;

    public BybitMarketClient(MarketClient marketClient, BybitWebSocketClient bybitWebSocketClient, Clock clock) {
        super(clock);
        this.marketClient = marketClient;
        this.bybitWebSocketClient = bybitWebSocketClient;
    }

    /**
     * 빈 초기화 시 공개 WebSocket의 ticker/kline 수신 콜백을 등록하고 거래 규칙 캐시를 채운다.
     */
    @PostConstruct
    public void init() {
        bybitWebSocketClient.addTickerCallback(this::onTickerReceived);
        bybitWebSocketClient.addKlineCallback(this::onKlineReceived);
        bybitWebSocketClient.addTradeCallback(this::onTradeReceived);
        refreshInstruments();
    }

    @Override
    public ExchangeType getExchangeType() {
        return ExchangeType.BYBIT;
    }

    // ─── REST ─────────────────────────────────────────

    /**
     * Bybit GET /v5/market/tickers (linear) 전체 티커를 조회한다.
     *
     * @return USDT 무기한·정식 상장 종목만, 24시간 거래대금 내림차순 (응답이 없으면 빈 목록)
     */
    @Override
    protected List<TickerDTO> fetchTickers() {
        FindTickerResponse response = marketClient.getTickers(Category.LINEAR.getCategory()).getBody();
        if (response == null || response.getResult() == null || response.getResult().getList() == null) {
            return List.of();
        }
        return response.getResult().getList().stream()
                // USDT 무기한만 남기고, 정식 상장 전(프리런치 진행 중) 종목은 제외 — 거래 규칙이 다름
                .filter(t -> t.getSymbol() != null && t.getSymbol().endsWith("USDT") && !isPreListing(t))
                // 24시간 거래대금(turnover) 내림차순 정렬 (Bybit 마켓 페이지 기준)
                .sorted(Comparator.comparingDouble(
                        (FindTickerResponse.TickerInfo t) -> parseOrZero(t.getTurnover24h())).reversed())
                .map(this::toTicker)
                .toList();
    }

    /**
     * Bybit GET /v5/market/instruments-info (linear) 전체 종목 거래 규칙을 조회한다.
     * 페이징 응답이므로 nextPageCursor가 빌 때까지 반복 호출한다.
     *
     * @return 종목별 수량 단위·가격 단위·최소 주문 수량
     */
    @Override
    protected List<InstrumentDTO> fetchInstruments() {
        List<InstrumentDTO> result = new ArrayList<>();
        String cursor = null; // 첫 호출은 커서 없이 시작

        do {
            GetInstrumentsInfoResponse response = marketClient.getInstrumentsInfo(
                    Category.LINEAR.getCategory(), INSTRUMENTS_PAGE_LIMIT, cursor).getBody();
            if (response == null || response.getResult() == null || response.getResult().getList() == null) {
                break;
            }

            for (GetInstrumentsInfoResponse.InstrumentInfo info : response.getResult().getList()) {
                // 수량 단위가 없으면 주문 수량 계산 불가 → 제외
                if (info.getSymbol() == null || info.getLotSizeFilter() == null
                        || info.getLotSizeFilter().getQtyStep() == null) {
                    continue;
                }
                result.add(InstrumentDTO.builder()
                        .symbol(info.getSymbol())
                        .qtyStep(info.getLotSizeFilter().getQtyStep())
                        .minOrderQty(info.getLotSizeFilter().getMinOrderQty())
                        .tickSize(info.getPriceFilter() != null ? info.getPriceFilter().getTickSize() : null)
                        .build());
            }

            // 다음 페이지 커서 (null 또는 빈 문자열이면 마지막 페이지)
            cursor = response.getResult().getNextPageCursor();
        } while (cursor != null && !cursor.isEmpty());

        return result;
    }

    /**
     * Bybit GET /v5/market/kline (linear) 캔들을 조회한다.
     *
     * @param symbol   종목 심볼 (예: "BTCUSDT")
     * @param interval 캔들 간격 (예: "1", "60", "D")
     * @param limit    최대 조회 건수 (최대 200)
     * @return 시작 시각 오름차순 캔들 목록 (endTime은 0)
     * @throws CustomException 응답이 없거나 retCode가 0이 아닌 경우 (MARKET_DATA_FAILED)
     */
    @Override
    public List<KlineDTO> getKlines(String symbol, String interval, int limit) {
        GetKlineResponse response = marketClient.getKline(Category.LINEAR.getCategory(), symbol, interval, limit).getBody();
        if (response == null || !"0".equals(response.getRetCode())
                || response.getResult() == null || response.getResult().getList() == null) {
            throw new CustomException(ExceptionMessage.MARKET_DATA_FAILED, retMsgOf(response));
        }

        // Bybit 응답은 최신순 [startTime, open, high, low, close, volume, turnover] → 오름차순으로 뒤집음
        List<KlineDTO> klines = new ArrayList<>();
        for (List<String> row : response.getResult().getList()) {
            klines.add(0, KlineDTO.builder()
                    .symbol(symbol)
                    .startTime(Long.parseLong(row.get(0)))
                    .open(row.get(1))
                    .high(row.get(2))
                    .low(row.get(3))
                    .close(row.get(4))
                    .volume(row.get(5))
                    .build());
        }
        return klines;
    }

    /**
     * Bybit GET /v5/market/orderbook (linear) 호가창을 조회한다.
     *
     * @param symbol 종목 심볼 (예: "BTCUSDT")
     * @param depth  호가 단계 수
     * @return 매도(asks, 가격 오름차순)·매수(bids, 가격 내림차순) 호가
     * @throws CustomException 응답이 없거나 retCode가 0이 아닌 경우 (MARKET_DATA_FAILED)
     */
    @Override
    public OrderBookDTO getOrderBook(String symbol, int depth) {
        OrderBookResponse response = marketClient.getOrderBook(Category.LINEAR.getCategory(), symbol, depth).getBody();
        if (response == null || !"0".equals(response.getRetCode()) || response.getResult() == null) {
            throw new CustomException(ExceptionMessage.MARKET_DATA_FAILED, retMsgOf(response));
        }

        OrderBookResponse.Result result = response.getResult();
        return OrderBookDTO.builder()
                .symbol(result.getS())
                .asks(toLevels(result.getA()))
                .bids(toLevels(result.getB()))
                .timestamp(result.getTs())
                .build();
    }

    // ─── WebSocket ────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    public void syncSubscriptions(Set<String> symbols) {
        bybitWebSocketClient.syncSubscriptions(symbols);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void syncTradeSubscriptions(Set<String> symbols) {
        bybitWebSocketClient.syncTradeSubscriptions(symbols);
    }

    /**
     * 공개 WebSocket publicTrade 메시지 수신 시 호출된다. 묶여 온 체결을 1건씩 공통 체결로 넘긴다.
     *
     * @param dto WebSocket 공개 체결 메시지
     */
    private void onTradeReceived(WebSocketPublicTradeDTO dto) {
        if (dto == null || dto.getData() == null) return;
        for (WebSocketPublicTradeDTO.TradeData data : dto.getData()) {
            if (data.getSymbol() == null || data.getQty() == null) continue;
            publishTrade(PublicTradeDTO.builder()
                    .symbol(data.getSymbol())
                    .tradeTime(data.getTradeTime())
                    .qty(data.getQty())
                    .build());
        }
    }

    /**
     * 공개 WebSocket ticker 메시지 수신 시 호출된다.
     * snapshot이면 전체 교체, delta이면 기존 티커에 변경된(non-null) 필드만 병합한 뒤 공통 처리에 넘긴다.
     *
     * @param dto WebSocket 티커 메시지
     */
    private void onTickerReceived(WebSocketTickerDTO dto) {
        if (dto == null || dto.getData() == null || dto.getData().getSymbol() == null) return;
        WebSocketTickerDTO.TickerData data = dto.getData();

        TickerDTO existing = getTicker(data.getSymbol());
        // snapshot 또는 기존 데이터 없음 → 전체 교체
        if ("snapshot".equals(dto.getType()) || existing == null) {
            publishTicker(TickerDTO.builder()
                    .symbol(data.getSymbol())
                    .lastPrice(data.getLastPrice())
                    .price24hPcnt(data.getPrice24hPcnt())
                    .highPrice24h(data.getHighPrice24h())
                    .lowPrice24h(data.getLowPrice24h())
                    .volume24h(data.getVolume24h())
                    .turnover24h(data.getTurnover24h())
                    .build());
            return;
        }

        // delta: 누락된 필드는 '변경 없음' → 기존 값 유지
        publishTicker(existing.toBuilder()
                .lastPrice(orElse(data.getLastPrice(), existing.getLastPrice()))
                .price24hPcnt(orElse(data.getPrice24hPcnt(), existing.getPrice24hPcnt()))
                .highPrice24h(orElse(data.getHighPrice24h(), existing.getHighPrice24h()))
                .lowPrice24h(orElse(data.getLowPrice24h(), existing.getLowPrice24h()))
                .volume24h(orElse(data.getVolume24h(), existing.getVolume24h()))
                .turnover24h(orElse(data.getTurnover24h(), existing.getTurnover24h()))
                .build());
    }

    /**
     * 공개 WebSocket kline 메시지 수신 시 호출된다.
     * 토픽에서 심볼을 추출하고 각 봉을 공통 캔들로 변환해 넘긴다. (마감 여부 필터는 공통 처리에서 수행)
     *
     * @param dto WebSocket kline 메시지
     */
    private void onKlineReceived(WebSocketKlineDTO dto) {
        if (dto == null || dto.getData() == null || dto.getTopic() == null) return;

        // 토픽에서 심볼 추출 (예: "kline.1.BTCUSDT" → "BTCUSDT")
        String topic = dto.getTopic();
        int lastDot = topic.lastIndexOf('.');
        if (lastDot < 0 || lastDot == topic.length() - 1) return;
        String symbol = topic.substring(lastDot + 1);

        for (WebSocketKlineDTO.KlineData k : dto.getData()) {
            publishKline(KlineDTO.builder()
                    .symbol(symbol)
                    .startTime(k.getStart() != null ? k.getStart() : 0L)
                    .endTime(k.getEnd() != null ? k.getEnd() : 0L)
                    .open(k.getOpen())
                    .high(k.getHigh())
                    .low(k.getLow())
                    .close(k.getClose())
                    .volume(k.getVolume())
                    .confirmed(Boolean.TRUE.equals(k.getConfirm()))
                    .build());
        }
    }

    // ─── 내부 헬퍼 ─────────────────────────────────────

    /**
     * 프리런치(정식 상장 전) 진행 중인 종목인지 판단한다.
     * curPreListingPhase가 비어 있으면 정식 종목, Finished면 정식 전환이 끝난 종목이다.
     */
    private boolean isPreListing(FindTickerResponse.TickerInfo ticker) {
        String phase = ticker.getCurPreListingPhase();
        return phase != null && !phase.isEmpty() && !"Finished".equals(phase);
    }

    /** REST 티커를 공통 티커로 변환한다. */
    private TickerDTO toTicker(FindTickerResponse.TickerInfo t) {
        return TickerDTO.builder()
                .symbol(t.getSymbol())
                .lastPrice(t.getLastPrice())
                .price24hPcnt(t.getPrice24hPcnt())
                .highPrice24h(t.getHighPrice24h())
                .lowPrice24h(t.getLowPrice24h())
                .volume24h(t.getVolume24h())
                .turnover24h(t.getTurnover24h())
                .build();
    }

    /** [[price, size], ...] 배열을 호가 단계 목록으로 변환한다. */
    private List<OrderBookDTO.Level> toLevels(List<String[]> rows) {
        if (rows == null) return List.of();
        return rows.stream()
                .map(row -> new OrderBookDTO.Level(row[0], row[1]))
                .toList();
    }

    private static double parseOrZero(String value) {
        try {
            return value != null ? Double.parseDouble(value) : 0d;
        } catch (NumberFormatException e) {
            return 0d;
        }
    }

    private static String orElse(String value, String fallback) {
        return value != null ? value : fallback;
    }

    private static String retMsgOf(BybitMasterDTO response) {
        return response != null ? response.getRetMsg() : "응답 없음";
    }
}
