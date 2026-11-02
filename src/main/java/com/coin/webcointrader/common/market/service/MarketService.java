package com.coin.webcointrader.common.market.service;

import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.ExchangeClientRegistry;
import com.coin.webcointrader.common.market.client.ExchangeMarketClient;
import com.coin.webcointrader.common.market.dto.InstrumentDTO;
import com.coin.webcointrader.common.market.dto.KlineDTO;
import com.coin.webcointrader.common.market.dto.OrderBookDTO;
import com.coin.webcointrader.common.market.dto.PriceTrendResponse;
import com.coin.webcointrader.common.market.dto.TickerDTO;
import com.coin.webcointrader.common.market.dto.TickerResponse;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 시세 데이터 서비스. (거래소 중립)
 * 거래소 타입으로 시세 클라이언트를 골라 위임하고,
 * 1초마다 각 거래소 티커 캐시를 갱신하며, 실시간 가격을 STOMP로 브라우저에 push한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarketService {

    // 차트 캔들 조회 건수
    private static final int KLINE_LIMIT = 200;
    // 호가창 조회 단계 수
    private static final int ORDER_BOOK_DEPTH = 50;
    // [사용자 정책 2026-10-27] 대시보드 거래 목록은 거래소 선택과 관계없이 Bybit 하나로 통일
    private static final ExchangeType DISPLAY_EXCHANGE = ExchangeType.BYBIT;
    // [사용자 정책 2026-11-02] 대시보드 "최근 1시간 추세": 1분에 1번 저장, 코인별 최근 60개 보관
    private static final long PRICE_TREND_INTERVAL_MS = 60_000L;
    private static final int PRICE_TREND_SIZE = 60;

    private final ExchangeClientRegistry registry;
    private final SimpMessagingTemplate messagingTemplate; // STOMP 메시지 전송 템플릿 (서버 → 브라우저 push용)
    // 심볼별 가격 기록. 스케줄 스레드가 쓰고 요청 스레드가 읽으므로 불변 맵을 통째로 교체한다
    private volatile Map<String, List<BigDecimal>> priceTrends = Map.of();

    /**
     * 애플리케이션 시작 시 각 거래소 실시간 티커를 STOMP /topic/price.{exchange}.{symbol}로 push하도록 리스너를 등록한다.
     * 거래소마다 같은 심볼이 있으므로 거래소별로 채널을 나눈다.
     */
    @PostConstruct
    public void init() {
        for (ExchangeMarketClient market : registry.markets()) {
            String prefix = "/topic/price." + market.getExchangeType() + "."; // 예: /topic/price.BYBIT.
            market.addTickerListener(ticker ->
                    messagingTemplate.convertAndSend(prefix + ticker.getSymbol(), TickerResponse.from(ticker)));
        }
    }

    /**
     * 1초마다 모든 거래소의 티커 캐시를 갱신한다. (대시보드 목록·REST fallback용)
     */
    @Scheduled(fixedRate = 1000)
    public void refreshTickers() {
        registry.markets().forEach(ExchangeMarketClient::refreshTickers);
    }

    /**
     * 1분마다 대시보드 목록의 현재가를 코인별로 저장한다. (최근 1시간 추세용, 거래소 추가 호출 없음)
     * 메모리에만 보관하므로 재시작하면 기록이 사라지고 약 1시간에 걸쳐 다시 채워진다.
     */
    @Scheduled(fixedRate = PRICE_TREND_INTERVAL_MS)
    public void recordPriceTrends() {
        List<TickerDTO> tickers = getDisplayTickers();
        // 빈 응답은 일시 오류로 보고 기존 기록 유지
        if (tickers.isEmpty()) {
            return;
        }

        // 현재 목록 기준으로 새로 만들어, 목록에서 사라진 코인의 기록은 자연히 제거된다
        Map<String, List<BigDecimal>> next = new HashMap<>();
        for (TickerDTO ticker : tickers) {
            List<BigDecimal> history = priceTrends.getOrDefault(ticker.getSymbol(), List.of());
            BigDecimal price = parsePrice(ticker.getLastPrice());
            next.put(ticker.getSymbol(), price == null ? history : appendPrice(history, price));
        }
        priceTrends = Map.copyOf(next);
    }

    /**
     * 코인별 최근 1시간 가격 추세를 반환한다.
     *
     * @return 심볼과 1분 간격 현재가(오래된 값부터, 최대 60개) 목록. 저장 전이면 빈 목록
     */
    public List<PriceTrendResponse> getPriceTrends() {
        return priceTrends.entrySet().stream()
                .map(entry -> new PriceTrendResponse(entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * 현재가 문자열을 숫자로 바꾼다.
     *
     * @param lastPrice 거래소 현재가 문자열
     * @return 양수 가격. 비어 있거나 숫자가 아니거나 0 이하면 null
     */
    private BigDecimal parsePrice(String lastPrice) {
        if (lastPrice == null || lastPrice.isBlank()) {
            return null;
        }
        try {
            BigDecimal price = new BigDecimal(lastPrice);
            return price.signum() > 0 ? price : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 기록 끝에 가격을 추가한 새 목록을 만든다. 보관 개수를 넘으면 가장 오래된 값부터 버린다.
     *
     * @param history 기존 기록 (오래된 값부터)
     * @param price   추가할 가격
     * @return 최대 PRICE_TREND_SIZE개의 불변 목록
     */
    private List<BigDecimal> appendPrice(List<BigDecimal> history, BigDecimal price) {
        List<BigDecimal> updated = new ArrayList<>(history);
        updated.add(price);
        int from = Math.max(0, updated.size() - PRICE_TREND_SIZE);
        return List.copyOf(updated.subList(from, updated.size()));
    }

    /**
     * 거래 가능 티커 목록을 반환한다. 캐시가 비어 있으면 한 번 갱신 후 반환한다.
     *
     * @param exchangeType 거래소 타입
     * @return 거래대금 내림차순 티커 목록 (조회 실패 시 빈 목록)
     */
    public List<TickerDTO> getTickers(ExchangeType exchangeType) {
        ExchangeMarketClient market = registry.market(exchangeType);
        // 기동 직후 등 캐시가 비어 있으면 즉시 갱신
        if (market.getTickers().isEmpty()) {
            market.refreshTickers();
        }
        return market.getTickers();
    }

    /**
     * 대시보드 표시용 티커 목록을 반환한다. 거래소 선택과 관계없이 {@link #DISPLAY_EXCHANGE} 기준이다.
     *
     * @return 거래대금 내림차순 티커 목록 (조회 실패 시 빈 목록)
     */
    public List<TickerDTO> getDisplayTickers() {
        return getTickers(DISPLAY_EXCHANGE);
    }

    /**
     * 실시간 스트림으로 받은 심볼의 최신 티커를 반환한다.
     *
     * @param exchangeType 거래소 타입
     * @param symbol       종목 심볼 (예: "BTCUSDT")
     * @return 최신 티커, 수신 이력이 없으면 null
     */
    public TickerDTO getTicker(ExchangeType exchangeType, String symbol) {
        return registry.market(exchangeType).getTicker(symbol);
    }

    /**
     * 실시간 스트림이 최근 5초 이내에 데이터를 받았는지 반환한다. (자동매매 REST fallback 스킵 판단용)
     *
     * @param exchangeType 거래소 타입
     * @return 스트림 활성 상태이면 true
     */
    public boolean isWsActive(ExchangeType exchangeType) {
        return registry.market(exchangeType).isWsActive();
    }

    /**
     * 최근 200개 캔들을 조회한다.
     *
     * @param exchangeType 거래소 타입
     * @param symbol       종목 심볼 (예: "BTCUSDT")
     * @param interval     캔들 간격 (예: "1", "5", "15", "60", "D")
     * @return 시작 시각 오름차순 캔들 목록
     */
    public List<KlineDTO> getKlines(ExchangeType exchangeType, String symbol, String interval) {
        return registry.market(exchangeType).getKlines(symbol, interval, KLINE_LIMIT);
    }

    /**
     * 매수·매도 각 상위 50단계 호가를 조회한다.
     *
     * @param exchangeType 거래소 타입
     * @param symbol       종목 심볼 (예: "BTCUSDT")
     * @return 호가창
     */
    public OrderBookDTO getOrderBook(ExchangeType exchangeType, String symbol) {
        return registry.market(exchangeType).getOrderBook(symbol, ORDER_BOOK_DEPTH);
    }

    /**
     * USDT 금액을 코인 수량으로 변환한다. (거래소 수량 단위로 내림)
     *
     * @param exchangeType 거래소 타입
     * @param symbol       종목 심볼 (예: "BTCUSDT")
     * @param usdtAmount   USDT 금액 (예: 15)
     * @param currentPrice 현재가 (예: 87000.5)
     * @return 코인 수량 문자열 (예: "0.001"), 거래 규칙 조회 실패 시 null, 최소 단위 미달 시 "0"
     */
    public String convertUsdtToQty(ExchangeType exchangeType, String symbol, BigDecimal usdtAmount, BigDecimal currentPrice) {
        return registry.market(exchangeType).convertUsdtToQty(symbol, usdtAmount, currentPrice);
    }

    /**
     * 심볼의 최소 가격 단위(tickSize)를 반환한다. (거래소 TP/SL 가격 단위 맞춤용)
     *
     * @param exchangeType 거래소 타입
     * @param symbol       종목 심볼 (예: "BTCUSDT")
     * @return tickSize 문자열 (예: "0.10"), 조회 실패 시 null
     */
    public String getTickSize(ExchangeType exchangeType, String symbol) {
        InstrumentDTO instrument = registry.market(exchangeType).getInstrument(symbol);
        return instrument != null ? instrument.getTickSize() : null;
    }

    /**
     * 거래소에서 심볼을 거래할 수 있는지 확인한다. (거래 규칙 조회 가능 여부 기준)
     *
     * @param exchangeType 거래소 타입
     * @param symbol       종목 심볼 (예: "BTCUSDT")
     * @return 거래 가능하면 true, 상장되지 않았거나 거래 규칙 조회 실패 시 false
     */
    public boolean isTradable(ExchangeType exchangeType, String symbol) {
        return registry.market(exchangeType).getInstrument(symbol) != null;
    }
}
