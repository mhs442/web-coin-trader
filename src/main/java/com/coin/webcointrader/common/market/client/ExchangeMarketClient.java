package com.coin.webcointrader.common.market.client;

import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.market.dto.InstrumentDTO;
import com.coin.webcointrader.common.market.dto.KlineDTO;
import com.coin.webcointrader.common.market.dto.OrderBookDTO;
import com.coin.webcointrader.common.market.dto.TickerDTO;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 거래소 시세 클라이언트 (API 명세).
 * USDT 무기한 종목의 시세·캔들·호가창·거래 규칙 조회와 실시간 스트림 구독을 제공한다.
 * 공통 처리는 {@link AbstractExchangeMarketClient}, 거래소별 차이는 그 하위 클래스가 구현한다.
 */
public interface ExchangeMarketClient {

    /** 이 클라이언트가 대상으로 하는 거래소 타입을 반환한다. */
    ExchangeType getExchangeType();

    /**
     * 캐시된 거래 가능 티커 목록을 반환한다.
     *
     * @return 24시간 거래대금 내림차순 티커 목록 (캐시 갱신 전이면 빈 목록)
     */
    List<TickerDTO> getTickers();

    /**
     * 캐시된 티커 목록을 거래소에서 다시 조회해 갱신한다. 실패 시 기존 캐시를 유지한다.
     */
    void refreshTickers();

    /**
     * 실시간 스트림으로 받은 심볼의 최신 티커를 반환한다.
     *
     * @param symbol 종목 심볼 (예: "BTCUSDT")
     * @return 최신 티커, 수신 이력이 없으면 null
     */
    TickerDTO getTicker(String symbol);

    /**
     * 캔들 목록을 조회한다.
     *
     * @param symbol   종목 심볼 (예: "BTCUSDT")
     * @param interval 봉 간격 (분 단위 숫자 문자열 "1"·"5"·"60" 등, 일봉 "D")
     * @param limit    조회 개수
     * @return 시작 시각 오름차순 캔들 목록
     */
    List<KlineDTO> getKlines(String symbol, String interval, int limit);

    /**
     * 호가창을 조회한다.
     *
     * @param symbol 종목 심볼 (예: "BTCUSDT")
     * @param depth  호가 단계 수
     * @return 호가창 (asks 오름차순, bids 내림차순)
     */
    OrderBookDTO getOrderBook(String symbol, int depth);

    /**
     * 종목 거래 규칙(수량·가격 단위)을 반환한다. 캐시에 없으면 갱신 후 조회한다.
     *
     * @param symbol 종목 심볼 (예: "BTCUSDT")
     * @return 거래 규칙, 갱신 후에도 없으면 null
     */
    InstrumentDTO getInstrument(String symbol);

    /**
     * USDT 금액을 주문 수량으로 변환한다. (qtyStep 단위 내림)
     *
     * @param symbol       종목 심볼 (예: "BTCUSDT")
     * @param usdtAmount   USDT 금액 (예: 15)
     * @param currentPrice 현재가
     * @return 수량 문자열 (예: "0.001"), 최소 단위 미달 시 "0", 거래 규칙 조회 실패 시 null
     */
    String convertUsdtToQty(String symbol, BigDecimal usdtAmount, BigDecimal currentPrice);

    /**
     * 실시간 시세 스트림이 살아 있는지 반환한다.
     *
     * @return 최근 5초 이내 티커 수신 시 true
     */
    boolean isWsActive();

    /**
     * 실시간 캔들 구독 심볼을 주어진 집합과 같게 맞춘다. (추가분 구독, 제외분 해지)
     *
     * @param symbols 구독할 심볼 집합
     */
    void syncSubscriptions(Set<String> symbols);

    /**
     * 실시간 티커 수신 리스너를 등록한다. (현재가가 바뀐 갱신만 전달)
     *
     * @param listener 최신 티커 전체(병합 완료)를 받는 리스너
     */
    void addTickerListener(Consumer<TickerDTO> listener);

    /**
     * 봉 마감 리스너를 등록한다. 마감된 봉(confirmed=true)만 전달된다.
     *
     * @param listener 마감된 캔들을 받는 리스너
     */
    void addKlineListener(Consumer<KlineDTO> listener);
}
