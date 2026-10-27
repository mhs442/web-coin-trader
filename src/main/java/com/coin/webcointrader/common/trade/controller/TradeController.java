package com.coin.webcointrader.common.trade.controller;

import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.enums.TradeMode;
import com.coin.webcointrader.common.login.dto.UserDTO;
import com.coin.webcointrader.common.trade.dto.PlaceOrderRequest;
import com.coin.webcointrader.common.trade.dto.PlaceOrderResponse;
import com.coin.webcointrader.common.trade.service.TradeFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class TradeController {

    private final TradeFacade tradeFacade;

    /**
     * 실전 시장가 주문.
     * exchange 파라미터는 필수 — 없으면 400(EXCHANGE_TYPE_REQUIRED), 지원하지 않는 값이면 400(INVALID_EXCHANGE_TYPE).
     */
    @PostMapping("/api/order/create")
    public PlaceOrderResponse createOrder(@RequestBody PlaceOrderRequest request,
                                          @RequestParam(required = false) String exchange,
                                          @AuthenticationPrincipal UserDTO user) {
        return PlaceOrderResponse.from(tradeFacade.placeOrder(
                request.toOrderRequest(), user.getId(), ExchangeType.from(exchange), TradeMode.MAIN));
    }
}
