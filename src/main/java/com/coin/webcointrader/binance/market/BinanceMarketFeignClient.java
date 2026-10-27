package com.coin.webcointrader.binance.market;

import com.coin.webcointrader.binance.common.BinancePublicFeignConfig;
import com.coin.webcointrader.binance.market.dto.BinanceDepthResponse;
import com.coin.webcointrader.binance.market.dto.BinanceExchangeInfoResponse;
import com.coin.webcointrader.binance.market.dto.BinancePriceTickerResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * Binance USDⓈ-M 선물 공개 시세 API 클라이언트. (인증 불필요)
 * 오류는 HTTP 4XX/5XX + {code,msg} 로 오며 BinanceErrorDecoder 가 BinanceApiException 으로 바꾼다.
 * 괄호 안 가중치는 IP당 분당 한도(2400)에서 차감되는 값이다.
 */
@FeignClient(name = "binanceMarketClient", url = "${binance.api.url}", configuration = BinancePublicFeignConfig.class)
public interface BinanceMarketFeignClient {

    /**
     * Binance GET /fapi/v2/ticker/price (symbol 생략 → 전체 종목, 가중치 2)
     *
     * @return 종목별 최근 체결가 목록
     */
    @GetMapping("/fapi/v2/ticker/price")
    List<BinancePriceTickerResponse> getPriceTickers();

    /**
     * Binance GET /fapi/v1/exchangeInfo (가중치 1, 페이징 없음)
     *
     * @return 전체 종목 정보와 거래 규칙 필터
     */
    @GetMapping("/fapi/v1/exchangeInfo")
    BinanceExchangeInfoResponse getExchangeInfo();

    /**
     * Binance GET /fapi/v1/klines (가중치: limit 100 미만 1, 500 미만 2, 1000 이하 5, 초과 10)
     *
     * @param symbol   종목 심볼 (예: "BTCUSDT")
     * @param interval 봉 간격 (예: "1m", "1h", "1d")
     * @param limit    조회 건수 (기본 500, 최대 1500)
     * @return 시작 시각 오름차순 캔들 배열 [시작시각, 시가, 고가, 저가, 종가, 거래량, 종료시각, ...]
     */
    @GetMapping("/fapi/v1/klines")
    List<List<String>> getKlines(@RequestParam("symbol") String symbol,
                                 @RequestParam("interval") String interval,
                                 @RequestParam("limit") int limit);

    /**
     * Binance GET /fapi/v1/depth (가중치: limit 5·10·20·50 → 2, 100 → 5, 500 → 10, 1000 → 20)
     *
     * @param symbol 종목 심볼 (예: "BTCUSDT")
     * @param limit  호가 단계 수 (5, 10, 20, 50, 100, 500, 1000 중 하나)
     * @return 호가창
     */
    @GetMapping("/fapi/v1/depth")
    BinanceDepthResponse getDepth(@RequestParam("symbol") String symbol,
                                  @RequestParam("limit") int limit);
}
