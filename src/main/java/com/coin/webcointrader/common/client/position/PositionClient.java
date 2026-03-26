package com.coin.webcointrader.common.client.position;

import com.coin.webcointrader.common.dto.request.SetLeverageRequest;
import com.coin.webcointrader.common.dto.request.SetMarginModeRequest;
import com.coin.webcointrader.common.dto.request.SetTradingStopRequest;
import com.coin.webcointrader.common.dto.response.GetPositionListResponse;
import com.coin.webcointrader.common.dto.response.SetLeverageResponse;
import com.coin.webcointrader.common.dto.response.SetMarginModeResponse;
import com.coin.webcointrader.common.dto.response.SetTradingStopResponse;
import com.coin.webcointrader.common.config.BybitFeignConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Bybit 포지션 관리 API 클라이언트.
 * HMAC-SHA256 서명 인증이 필요한 포지션 관련 엔드포인트를 제공한다.
 */
@FeignClient(name = "positionClient", url = "${bybit.api.url}", configuration = BybitFeignConfig.class)
public interface PositionClient {

    /**
     * Bybit GET /v5/position/list
     * 특정 종목의 현재 오픈 포지션 목록을 조회한다.
     *
     * @param category 파생상품 카테고리 (예: "linear")
     * @param symbol   종목 심볼 (예: "BTCUSDT")
     * @return 포지션 목록 응답 (side, size, avgPrice, unrealisedPnl, leverage 등 포함)
     */
    @GetMapping("/v5/position/list")
    ResponseEntity<GetPositionListResponse> getPositionList(
            @RequestParam("category") String category,
            @RequestParam("symbol") String symbol
    );

    /**
     * Bybit POST /v5/account/set-margin-mode
     * UTA 계정의 마진 모드를 변경한다. (열린 포지션/주문이 없는 상태에서만 가능)
     *
     * @param request 마진 모드 설정 요청 (setMarginMode: "ISOLATED_MARGIN", "REGULAR_MARGIN", "PORTFOLIO_MARGIN")
     * @return 마진 모드 설정 결과 응답
     */
    @PostMapping("/v5/account/set-margin-mode")
    ResponseEntity<SetMarginModeResponse> setMarginMode(@RequestBody SetMarginModeRequest request);

    /**
     * Bybit POST /v5/position/set-leverage
     * 특정 종목의 매수·매도 레버리지를 설정한다.
     *
     * @param request 레버리지 설정 요청 (category, symbol, buyLeverage, sellLeverage 포함)
     * @return 레버리지 설정 결과 응답
     */
    @PostMapping("/v5/position/set-leverage")
    ResponseEntity<SetLeverageResponse> setLeverage(@RequestBody SetLeverageRequest request);

    /**
     * Bybit POST /v5/position/trading-stop
     * 특정 종목의 손절(Stop Loss)·익절(Take Profit) 가격을 설정한다.
     *
     * @param request 손절·익절 설정 요청 (category, symbol, takeProfit, stopLoss, positionIdx 포함)
     * @return 손절·익절 설정 결과 응답
     */
    @PostMapping("/v5/position/trading-stop")
    ResponseEntity<SetTradingStopResponse> setTradingStop(@RequestBody SetTradingStopRequest request);
}
