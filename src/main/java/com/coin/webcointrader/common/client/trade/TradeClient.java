package com.coin.webcointrader.common.client.trade;

import com.coin.webcointrader.common.dto.request.CreateOrderRequest;
import com.coin.webcointrader.common.dto.response.CreateOrderResponse;
import com.coin.webcointrader.common.dto.response.GetExecutionListResponse;
import com.coin.webcointrader.common.config.BybitFeignConfig;
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
     * @param limit    조회 건수 (최대 100)
     * @return 체결 내역 목록 (execPrice, execQty, execFee 포함)
     */
    @GetMapping("/v5/execution/list")
    ResponseEntity<GetExecutionListResponse> getExecutionList(
            @RequestParam("category") String category,
            @RequestParam("symbol") String symbol,
            @RequestParam("orderId") String orderId,
            @RequestParam("limit") int limit
    );
}