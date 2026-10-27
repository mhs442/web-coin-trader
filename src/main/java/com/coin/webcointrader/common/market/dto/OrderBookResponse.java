package com.coin.webcointrader.common.market.dto;

import com.coin.webcointrader.common.market.dto.OrderBookDTO;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 호가창 응답. (차트 /api/orderbook)
 */
@Getter
@Builder
public class OrderBookResponse {
    private String symbol;        // 종목 심볼 (예: BTCUSDT)
    private List<Level> asks;     // 매도 호가 (가격 오름차순)
    private List<Level> bids;     // 매수 호가 (가격 내림차순)
    private long timestamp;       // 호가 기준 시각 (epoch ms)

    /**
     * 호가 1단계.
     */
    @Getter
    @Builder
    public static class Level {
        private String price;     // 호가 가격
        private String qty;       // 잔량 (코인 기준)
    }

    /**
     * 공통 호가창을 응답으로 변환한다.
     *
     * @param book 공통 호가창
     * @return 호가창 응답
     */
    public static OrderBookResponse from(OrderBookDTO book) {
        return OrderBookResponse.builder()
                .symbol(book.getSymbol())
                .asks(toLevels(book.getAsks()))
                .bids(toLevels(book.getBids()))
                .timestamp(book.getTimestamp())
                .build();
    }

    private static List<Level> toLevels(List<OrderBookDTO.Level> levels) {
        if (levels == null) return List.of();
        return levels.stream()
                .map(l -> Level.builder().price(l.getPrice()).qty(l.getQty()).build())
                .toList();
    }
}
