package com.coin.webcointrader.common.market.client;

import com.coin.webcointrader.common.market.dto.InstrumentDTO;
import com.coin.webcointrader.common.market.dto.KlineDTO;
import com.coin.webcointrader.common.market.dto.PublicTradeDTO;
import com.coin.webcointrader.common.market.dto.TickerDTO;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 거래소 시세 클라이언트 공통 처리.
 * 티커·거래 규칙 캐시, USDT→수량 변환, 실시간 리스너 팬아웃, 스트림 활성 판정을 담당한다.
 * 하위 클래스는 거래소 조회({@link #fetchTickers()}, {@link #fetchInstruments()})와
 * 캔들·호가창·구독을 구현하고, 스트림 수신 시 {@link #publishTicker}/{@link #publishKline}을 호출한다.
 */
@Slf4j
public abstract class AbstractExchangeMarketClient implements ExchangeMarketClient {

    // 스트림 활성 판정 기준 (ms, 마지막 티커 수신 후 이 시간 이내면 활성)
    protected static final long WS_ACTIVE_THRESHOLD_MS = 5_000L;

    protected final Clock clock;

    // 거래 가능 티커 목록 캐시 (거래대금 내림차순)
    private volatile List<TickerDTO> cachedTickers = List.of();
    // 스트림으로 받은 심볼별 최신 티커
    private final Map<String, TickerDTO> latestTickers = new ConcurrentHashMap<>();
    // 심볼별 거래 규칙 캐시
    private final Map<String, InstrumentDTO> instruments = new ConcurrentHashMap<>();
    // 마지막 티커 수신 시각 (epoch ms)
    private volatile long lastTickMs = 0;

    private final List<Consumer<TickerDTO>> tickerListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<KlineDTO>> klineListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<PublicTradeDTO>> tradeListeners = new CopyOnWriteArrayList<>();

    protected AbstractExchangeMarketClient(Clock clock) {
        this.clock = clock;
    }

    // ─── 하위 클래스 구현 ───

    /**
     * 거래소에서 거래 가능 티커 목록을 조회한다.
     *
     * @return 거래대금 내림차순 티커 목록 (USDT 무기한·정식 상장 종목만)
     */
    protected abstract List<TickerDTO> fetchTickers();

    /**
     * 거래소에서 전체 종목 거래 규칙을 조회한다.
     *
     * @return 종목별 거래 규칙 목록
     */
    protected abstract List<InstrumentDTO> fetchInstruments();

    // ─── 공통 처리 ───

    /**
     * {@inheritDoc}
     */
    @Override
    public List<TickerDTO> getTickers() {
        return cachedTickers;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void refreshTickers() {
        try {
            List<TickerDTO> tickers = fetchTickers();
            // 빈 응답은 일시 오류로 보고 기존 캐시 유지
            if (tickers != null && !tickers.isEmpty()) {
                cachedTickers = List.copyOf(tickers);
            }
        } catch (Exception e) {
            log.warn("[{} 티커 갱신 실패] 기존 캐시 유지: {}", getExchangeType(), e.getMessage());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TickerDTO getTicker(String symbol) {
        return latestTickers.get(symbol);
    }

    /**
     * 거래 규칙 캐시를 거래소에서 다시 조회해 갱신한다. 실패 시 기존 캐시를 유지한다.
     */
    public void refreshInstruments() {
        try {
            List<InstrumentDTO> list = fetchInstruments();
            if (list == null) return;
            list.forEach(info -> instruments.put(info.getSymbol(), info));
            log.info("[{} 거래 규칙 캐시] {}개 종목", getExchangeType(), instruments.size());
        } catch (Exception e) {
            log.warn("[{} 거래 규칙 갱신 실패] {}", getExchangeType(), e.getMessage());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public InstrumentDTO getInstrument(String symbol) {
        InstrumentDTO info = instruments.get(symbol);
        // 캐시 미스 → 신규 상장 등일 수 있으므로 갱신 후 재조회
        if (info == null) {
            refreshInstruments();
            info = instruments.get(symbol);
        }
        return info;
    }

    /**
     * {@inheritDoc}
     * <p>계산식: qty = floor(usdtAmount / currentPrice / qtyStep) × qtyStep</p>
     */
    @Override
    public String convertUsdtToQty(String symbol, BigDecimal usdtAmount, BigDecimal currentPrice) {
        InstrumentDTO info = getInstrument(symbol);
        if (info == null || info.getQtyStep() == null) {
            log.error("[{} 수량 변환 실패] qtyStep 조회 불가 symbol={}", getExchangeType(), symbol);
            return null;
        }

        BigDecimal qtyStep = new BigDecimal(info.getQtyStep());
        // 소수점 자릿수 계산 (예: "0.001" → scale=3)
        int scale = Math.max(qtyStep.stripTrailingZeros().scale(), 0);

        BigDecimal rawQty = usdtAmount.divide(currentPrice, scale + 4, RoundingMode.HALF_UP);
        BigDecimal steps = rawQty.divide(qtyStep, 0, RoundingMode.DOWN); // 내림
        BigDecimal qty = steps.multiply(qtyStep);

        // 최소 주문 단위 미달 → null(조회 실패)과 구분하기 위해 "0" 반환 (호출부에서 이번 틱 스킵)
        if (qty.signum() <= 0) {
            log.warn("[{} 수량 변환] 최소 단위 미달 symbol={}, usdt={}, price={}, qtyStep={}",
                    getExchangeType(), symbol, usdtAmount, currentPrice, qtyStep);
            return "0";
        }
        return qty.stripTrailingZeros().toPlainString();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isWsActive() {
        return lastTickMs > 0 && clock.millis() - lastTickMs < WS_ACTIVE_THRESHOLD_MS;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addTickerListener(Consumer<TickerDTO> listener) {
        tickerListeners.add(listener);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addKlineListener(Consumer<KlineDTO> listener) {
        klineListeners.add(listener);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addTradeListener(Consumer<PublicTradeDTO> listener) {
        tradeListeners.add(listener);
    }

    /**
     * 스트림으로 받은 최신 티커(병합 완료)를 저장하고, 현재가가 바뀐 경우에만 리스너에 전달한다.
     * (거래량 등 다른 필드만 바뀐 갱신은 가격 처리·STOMP push가 필요 없으므로 생략)
     *
     * @param ticker 최신 티커 전체
     */
    protected void publishTicker(TickerDTO ticker) {
        lastTickMs = clock.millis();
        TickerDTO previous = latestTickers.put(ticker.getSymbol(), ticker);
        // 현재가가 없거나 직전과 같으면 리스너 전달 생략
        if (ticker.getLastPrice() == null
                || (previous != null && ticker.getLastPrice().equals(previous.getLastPrice()))) {
            return;
        }
        // 한 리스너의 예외가 다른 리스너 전달을 막지 않도록 개별 처리
        for (Consumer<TickerDTO> listener : tickerListeners) {
            try {
                listener.accept(ticker);
            } catch (Exception e) {
                log.error("[{} 티커 리스너 오류] symbol={}: {}", getExchangeType(), ticker.getSymbol(), e.getMessage());
            }
        }
    }

    /**
     * 스트림으로 받은 캔들을 리스너에 전달한다. 마감되지 않은 봉은 무시한다.
     *
     * @param kline 캔들
     */
    protected void publishKline(KlineDTO kline) {
        // 봉 마감 신호만 전달 (진행 중인 봉 갱신은 무시)
        if (!kline.isConfirmed()) return;
        for (Consumer<KlineDTO> listener : klineListeners) {
            try {
                listener.accept(kline);
            } catch (Exception e) {
                log.error("[{} 캔들 리스너 오류] symbol={}: {}", getExchangeType(), kline.getSymbol(), e.getMessage());
            }
        }
    }

    /**
     * 스트림으로 받은 공개 체결 1건을 리스너에 전달한다.
     *
     * @param trade 공개 체결
     */
    protected void publishTrade(PublicTradeDTO trade) {
        for (Consumer<PublicTradeDTO> listener : tradeListeners) {
            try {
                listener.accept(trade);
            } catch (Exception e) {
                log.error("[{} 체결 리스너 오류] symbol={}: {}", getExchangeType(), trade.getSymbol(), e.getMessage());
            }
        }
    }
}
