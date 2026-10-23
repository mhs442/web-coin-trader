package com.coin.webcointrader.bybit.client.trade;

import com.coin.webcointrader.bybit.dto.request.CreateOrderRequest;
import com.coin.webcointrader.bybit.dto.response.CreateOrderResponse;
import com.coin.webcointrader.bybit.dto.response.GetExecutionListResponse;
import com.coin.webcointrader.bybit.dto.response.GetOpenOrdersResponse;
import com.coin.webcointrader.bybit.config.BybitFeignConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Bybit 주문 생성 API 클라이언트.
 * HMAC-SHA256 서명 인증이 필요한 주문 관련 엔드포인트를 제공한다.
 */
@FeignClient(name = "tradeClient", url = "${bybit.api.url}", configuration = BybitFeignConfig.class)
public interface TradeClient {

    /**
     * Bybit POST /v5/order/create
     * 지정가 또는 시장가 주문을 생성한다.
     *
     * @param request 주문 요청 객체 (category, symbol, side, orderType, qty 등 포함)
     * @return 주문 생성 응답 (Bybit 주문 ID 포함)
     */
    @PostMapping("/v5/order/create")
    ResponseEntity<CreateOrderResponse> createOrder(@RequestBody CreateOrderRequest request);

    /**
     * Bybit GET /v5/execution/list
     * 주문 ID 기준으로 실체결 정보(체결가, 수량, 수수료)를 조회한다.
     *
     * @param category 상품 유형 (예: "linear")
     * @param symbol   심볼 (예: "BTCUSDT")
     * @param orderId  조회할 주문 ID
     * @param limit    조회 건수 (1~100, 기본 50)
     * @param cursor   다음 페이지 커서 (이전 응답의 nextPageCursor, 첫 조회 시 null)
     * @return 체결 내역 목록 (execPrice, execQty, execFee, orderQty, nextPageCursor 포함)
     *         — 주문 1건이 여러 체결 레코드로 나뉠 수 있음
     */
    @GetMapping("/v5/execution/list")
    ResponseEntity<GetExecutionListResponse> getExecutionList(
            @RequestParam("category") String category,
            @RequestParam("symbol") String symbol,
            @RequestParam("orderId") String orderId,
            @RequestParam("limit") int limit,
            @RequestParam(value = "cursor", required = false) String cursor
    );

    /**
     * Bybit GET /v5/order/realtime
     * 주문 상태를 조회한다. orderLinkId로 조회하면 openOnly와 무관하게
     * 미체결 주문과 최근 종료 주문(Filled/Cancelled/Rejected, 계정·카테고리당 최근 500건)을 모두 반환한다.
     *
     * @param category    상품 유형 (예: "linear")
     * @param symbol      심볼 (예: "BTCUSDT", linear는 symbol/baseCoin/settleCoin 중 하나 필수)
     * @param orderLinkId 주문 생성 시 지정한 클라이언트 주문 ID
     * @return 주문 목록 (orderId, orderLinkId, orderStatus, cumExecQty, avgPrice 포함), 없으면 빈 목록
     */
    @GetMapping("/v5/order/realtime")
    ResponseEntity<GetOpenOrdersResponse> getOpenOrders(
            @RequestParam("category") String category,
            @RequestParam("symbol") String symbol,
            @RequestParam("orderLinkId") String orderLinkId
    );
}