package com.coin.webcointrader.common.client.market;

import com.coin.webcointrader.common.dto.response.FindTickerResponse;
import com.coin.webcointrader.common.dto.response.GetKlineResponse;
import com.coin.webcointrader.common.dto.response.OrderBookResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Bybit 시세 조회 API 클라이언트.
 * 인증 없이 호출 가능한 공개 마켓 데이터 엔드포인트를 제공한다.
 */
@FeignClient(name = "marketClient", url = "${bybit.api.url}")
public interface MarketClient {

    /**
     * Bybit GET /v5/market/tickers
     * 카테고리별 전체 종목의 현재가·거래량 등 실시간 티커 정보를 조회한다.
     *
     * @param category 파생상품 카테고리 (예: "linear", "inverse", "spot")
     * @return 티커 목록 응답
     */
    @GetMapping("/v5/market/tickers")
    ResponseEntity<FindTickerResponse> getTickers(@RequestParam("category") String category);

    /**
     * Bybit GET /v5/market/kline
     * 특정 종목의 캔들스틱(K-라인) 데이터를 조회한다.
     *
     * @param category 파생상품 카테고리 (예: "linear")
     * @param symbol   종목 심볼 (예: "BTCUSDT")
     * @param interval 캔들 인터벌 (예: "1", "5", "15", "60", "D")
     * @param limit    최대 조회 건수 (최대 200)
     * @return K-라인 데이터 응답
     */
    @GetMapping("/v5/market/kline")
    ResponseEntity<GetKlineResponse> getKline(@RequestParam("category") String category,
                                              @RequestParam("symbol") String symbol,
                                              @RequestParam("interval") String interval,
                                              @RequestParam("limit") int limit);

    /**
     * Bybit GET /v5/market/orderbook
     * 특정 종목의 호가창(오더북) 데이터를 조회한다.
     *
     * @param category 파생상품 카테고리 (예: "linear")
     * @param symbol   종목 심볼 (예: "BTCUSDT")
     * @param limit    조회할 호가 수 (각 매수·매도 측)
     * @return 호가창 응답
     */
    @GetMapping("/v5/market/orderbook")
    ResponseEntity<OrderBookResponse> getOrderBook(@RequestParam("category") String category,
                                                   @RequestParam("symbol") String symbol,
                                                   @RequestParam("limit") int limit);
}
