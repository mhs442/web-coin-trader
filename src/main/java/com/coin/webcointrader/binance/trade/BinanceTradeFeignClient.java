package com.coin.webcointrader.binance.trade;

import com.coin.webcointrader.binance.common.BinanceFeignConfig;
import com.coin.webcointrader.binance.trade.dto.BinanceAlgoOrderHistoryResponse;
import com.coin.webcointrader.binance.trade.dto.BinanceAlgoOrderRequest;
import com.coin.webcointrader.binance.trade.dto.BinanceAlgoOrderResponse;
import com.coin.webcointrader.binance.trade.dto.BinanceIncomeResponse;
import com.coin.webcointrader.binance.trade.dto.BinanceOrderRequest;
import com.coin.webcointrader.binance.trade.dto.BinanceOrderResponse;
import com.coin.webcointrader.binance.trade.dto.BinancePositionRiskResponse;
import com.coin.webcointrader.binance.trade.dto.BinanceUserTradeResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.SpringQueryMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * Binance USDⓈ-M 선물 거래 API 클라이언트. (SIGNED — 키는 UserApiKeyContext 에서 읽어 서명)
 * 모든 파라미터는 쿼리 문자열로 보내며, 오류는 BinanceErrorDecoder 가 BinanceApiException 으로 바꾼다.
 * 가중치는 IP 기준 1분 2400 한도.
 */
@FeignClient(name = "binanceTradeClient", url = "${binance.api.url}", configuration = BinanceFeignConfig.class)
public interface BinanceTradeFeignClient {

    /**
     * Binance POST /fapi/v1/order (TRADE, IP 가중치 0 — 주문 수 한도만 차감)
     *
     * @param request 주문 파라미터 (null 필드 생략)
     * @return 주문 접수 결과 (시장가도 접수 시점 status NEW 가능)
     */
    @PostMapping("/fapi/v1/order")
    BinanceOrderResponse newOrder(@SpringQueryMap BinanceOrderRequest request);

    /**
     * Binance DELETE /fapi/v1/order (TRADE, 가중치 1)
     * 미체결 주문을 취소한다. 이미 전부 체결·취소된 주문은 -2011(Unknown order sent).
     *
     * @param symbol            종목 심볼
     * @param origClientOrderId 취소할 주문의 사용자 지정 ID
     * @return 취소된 주문 상태 (status CANCELED)
     */
    @DeleteMapping("/fapi/v1/order")
    BinanceOrderResponse cancelOrder(@RequestParam("symbol") String symbol,
                                     @RequestParam("origClientOrderId") String origClientOrderId);

    /**
     * Binance GET /fapi/v1/order (USER_DATA, 가중치 1)
     * orderId·origClientOrderId 중 하나는 필수. 없는 주문은 -2013.
     *
     * @param symbol            종목 심볼
     * @param orderId           거래소 주문 ID (null 가능)
     * @param origClientOrderId 사용자 지정 주문 ID (null 가능)
     * @return 주문 상태
     */
    @GetMapping("/fapi/v1/order")
    BinanceOrderResponse getOrder(@RequestParam("symbol") String symbol,
                                  @RequestParam(value = "orderId", required = false) String orderId,
                                  @RequestParam(value = "origClientOrderId", required = false) String origClientOrderId);

    /**
     * Binance POST /fapi/v1/algoOrder (TRADE, IP 가중치 0) — 조건부 TP/SL 주문
     *
     * @param request 알고 주문 파라미터 (null 필드 생략)
     * @return 알고 주문 접수 결과
     */
    @PostMapping("/fapi/v1/algoOrder")
    BinanceAlgoOrderResponse newAlgoOrder(@SpringQueryMap BinanceAlgoOrderRequest request);

    /**
     * Binance DELETE /fapi/v1/algoOpenOrders (TRADE, 가중치 1) — 심볼의 미체결 알고 주문 전체 취소
     *
     * @param symbol 종목 심볼
     */
    @DeleteMapping("/fapi/v1/algoOpenOrders")
    void cancelAllAlgoOpenOrders(@RequestParam("symbol") String symbol);

    /**
     * Binance POST /fapi/v1/marginType (TRADE, 가중치 1) — 이미 같은 모드면 -4046
     *
     * @param symbol     종목 심볼
     * @param marginType ISOLATED / CROSSED
     */
    @PostMapping("/fapi/v1/marginType")
    void changeMarginType(@RequestParam("symbol") String symbol, @RequestParam("marginType") String marginType);

    /**
     * Binance POST /fapi/v1/leverage (TRADE, 가중치 1)
     *
     * @param symbol   종목 심볼
     * @param leverage 레버리지 (1~125)
     */
    @PostMapping("/fapi/v1/leverage")
    void changeLeverage(@RequestParam("symbol") String symbol, @RequestParam("leverage") String leverage);

    /**
     * Binance GET /fapi/v1/userTrades (USER_DATA, 가중치 5)
     * orderId 지정 시 해당 주문의 체결만, startTime 지정 시 그 이후 최대 7일 범위를 조회한다.
     *
     * @param symbol    종목 심볼
     * @param orderId   거래소 주문 ID (null 가능)
     * @param startTime 조회 시작 시각 epoch ms (null 가능)
     * @param limit     최대 건수 (최대 1000)
     * @return 체결 목록 (시간 오름차순)
     */
    @GetMapping("/fapi/v1/userTrades")
    List<BinanceUserTradeResponse> getUserTrades(@RequestParam("symbol") String symbol,
                                                 @RequestParam(value = "orderId", required = false) String orderId,
                                                 @RequestParam(value = "startTime", required = false) Long startTime,
                                                 @RequestParam("limit") int limit);

    /**
     * Binance GET /fapi/v1/income (USER_DATA, 가중치 30)
     *
     * @param symbol     종목 심볼
     * @param incomeType 내역 유형 (FUNDING_FEE)
     * @param startTime  조회 시작 시각 epoch ms
     * @param endTime    조회 종료 시각 epoch ms
     * @param limit      최대 건수 (최대 1000)
     * @return 손익 내역 목록
     */
    @GetMapping("/fapi/v1/income")
    List<BinanceIncomeResponse> getIncome(@RequestParam("symbol") String symbol,
                                          @RequestParam("incomeType") String incomeType,
                                          @RequestParam("startTime") long startTime,
                                          @RequestParam("endTime") long endTime,
                                          @RequestParam("limit") int limit);

    /**
     * Binance GET /fapi/v3/positionRisk (USER_DATA, 가중치 5) — 포지션 없으면 빈 목록
     *
     * @param symbol 종목 심볼
     * @return 포지션 목록 (단방향 모드는 심볼당 최대 1건)
     */
    @GetMapping("/fapi/v3/positionRisk")
    List<BinancePositionRiskResponse> getPositionRisk(@RequestParam("symbol") String symbol);

    /**
     * Binance GET /fapi/v1/allAlgoOrders (USER_DATA, 가중치 5)
     * 조건부(알고) 주문 이력. 조회 범위는 최대 7일이며, 트리거된 주문은 actualOrderId 에 실제 생성된 주문 ID 가 들어간다.
     * (생성 후 90일 경과 주문, 체결 없이 취소·만료된 지 3일 지난 주문은 조회되지 않음)
     *
     * @param symbol    종목 심볼
     * @param startTime 조회 시작 시각 epoch ms
     * @param limit     최대 건수 (기본 500, 최대 1000)
     * @return 조건부 주문 이력 목록
     */
    @GetMapping("/fapi/v1/allAlgoOrders")
    List<BinanceAlgoOrderHistoryResponse> getAllAlgoOrders(@RequestParam("symbol") String symbol,
                                                           @RequestParam("startTime") long startTime,
                                                           @RequestParam("limit") int limit);
}
