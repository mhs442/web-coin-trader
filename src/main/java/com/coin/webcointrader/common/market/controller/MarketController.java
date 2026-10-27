package com.coin.webcointrader.common.market.controller;

import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.market.dto.KlineResponse;
import com.coin.webcointrader.common.market.dto.OrderBookResponse;
import com.coin.webcointrader.common.market.dto.TickerResponse;
import com.coin.webcointrader.common.market.service.MarketService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class MarketController {

    private final MarketService marketService;

    @GetMapping("/dashboard")
    public String dashBoard() {
        // 코인 목록은 화면에서 /api/tickers로 조회
        return "home/dash-board";
    }

    /**
     * 대시보드 거래 목록 (거래대금 내림차순).
     * 거래소 선택과 관계없이 Bybit 목록 하나로 통일한다. (MarketService.getDisplayTickers)
     */
    @GetMapping("/api/tickers")
    @ResponseBody
    public List<TickerResponse> getTickersApi() {
        return marketService.getDisplayTickers().stream()
                .map(TickerResponse::from)
                .toList();
    }

    @GetMapping("/chart")
    public String chartPage(@RequestParam String symbol, Model model) {
        model.addAttribute("symbol", symbol);
        return "home/chart";
    }

    /**
     * 최근 200개 캔들 (시작 시각 오름차순).
     */
    @GetMapping("/api/kline")
    @ResponseBody
    public List<KlineResponse> getKlineData(@RequestParam String symbol, @RequestParam String interval,
                                            @RequestParam(required = false) String exchange) {
        return marketService.getKlines(ExchangeType.from(exchange), symbol, interval).stream()
                .map(KlineResponse::from)
                .toList();
    }

    /**
     * 매수·매도 각 상위 50단계 호가.
     */
    @GetMapping("/api/orderbook")
    @ResponseBody
    public OrderBookResponse getOrderBookData(@RequestParam String symbol,
                                              @RequestParam(required = false) String exchange) {
        return OrderBookResponse.from(marketService.getOrderBook(ExchangeType.from(exchange), symbol));
    }
}
