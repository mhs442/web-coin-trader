package com.coin.webcointrader.binance.stream.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * Binance 개인 스트림으로 집계한 주문 1건의 체결 결과. (BinanceExecutionWaiter → BinanceTradeClient)
 * 수수료는 자산별로 나눠 담고, USDT 환산은 호출부(BinanceTradeClient)가 시세 캐시로 한다.
 */
@Getter
@Builder
public class BinanceFilledOrderDTO {

    private String symbol;          // 종목 심볼 (예: BTCUSDT)
    private String orderId;         // 거래소 주문 ID
    private String side;            // 주문 방향 (BUY / SELL)
    private String orderQty;        // 원 주문 수량 (부분 체결 판단용)
    private String avgPrice;        // 수량 가중 평균 체결가 = Σ(체결가×수량) / Σ수량
    private String filledQty;       // 체결 수량 합
    private String usdtFee;         // USDT 로 낸 수수료 합
    private String bnbFee;          // BNB 로 낸 수수료 합 (BNB 할인 사용 시, 환산 전)
    private boolean otherFeeAsset;  // USDT·BNB 외 자산 수수료 존재 여부 (true면 호출부가 추정치로 대체)
    private String execTime;        // 첫 체결 시각 (epoch ms 문자열)
}
