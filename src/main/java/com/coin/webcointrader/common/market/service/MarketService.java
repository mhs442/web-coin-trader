package com.coin.webcointrader.common.market.service;

import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.ExchangeClientRegistry;
import com.coin.webcointrader.common.market.client.ExchangeMarketClient;
import com.coin.webcointrader.common.market.dto.InstrumentDTO;
import com.coin.webcointrader.common.market.dto.KlineDTO;
import com.coin.webcointrader.common.market.dto.OrderBookDTO;
import com.coin.webcointrader.common.market.dto.TickerDTO;
import com.coin.webcointrader.common.market.dto.TickerResponse;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

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

    private final ExchangeClientRegistry registry;
    private final SimpMessagingTemplate messagingTemplate; // STOMP 메시지 전송 템플릿 (서버 → 브라우저 push용)

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
