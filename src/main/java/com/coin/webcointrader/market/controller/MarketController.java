package com.coin.webcointrader.market.controller;

import com.coin.webcointrader.common.dto.response.FindTickerResponse;
import com.coin.webcointrader.common.dto.response.GetKlineResponse;
import com.coin.webcointrader.common.dto.response.OrderBookResponse;
import com.coin.webcointrader.market.service.MarketService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
@RequiredArgsConstructor
public class MarketController {

    private final MarketService marketService;

    @GetMapping("/dashboard")
    public String dashBoard(Model model) {
        FindTickerResponse tickerResponse = marketService.getTickers();

        if (tickerResponse != null && tickerResponse.getResult() != null && tickerResponse.getResult().getList() != null) {
            model.addAttribute("coins", tickerResponse.getResult().getList());
        }

        return "home/dash-board";
    }

    @GetMapping("/api/tickers")
    @ResponseBody
    public FindTickerResponse getTickersApi() {
        return marketService.getTickers();
    }

    @GetMapping("/chart")
    public String chartPage(@RequestParam String symbol, Model model) {
        model.addAttribute("symbol", symbol);
        return "home/chart";
    }

    @GetMapping("/api/kline")
    @ResponseBody
    public GetKlineResponse getKlineData(@RequestParam String symbol, @RequestParam String interval) {
        return marketService.getKline(symbol, interval);
    }

    @GetMapping("/api/orderbook")
    @ResponseBody
    public OrderBookResponse getOrderBookData(@RequestParam String symbol) {
        return marketService.getOrderBook(symbol);
    }
}
