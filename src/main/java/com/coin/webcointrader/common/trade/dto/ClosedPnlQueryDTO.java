package com.coin.webcointrader.common.trade.dto;

import com.coin.webcointrader.common.enums.ExchangeType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

/**
 * 청산 손익 조회 조건.
 * 포지션 단위 손익을 주는 거래소(Bybit)는 orderId·symbol만 쓰고,
 * 체결·펀딩 내역으로 직접 계산하는 거래소(Binance)는 entryTime·entryFee까지 쓴다.
 */
@Getter
@SuperBuilder
@NoArgsConstructor
public class ClosedPnlQueryDTO {
    private ExchangeType exchangeType; // 조회할 거래소
    private String orderId;            // 청산 주문 ID
    private String symbol;             // 종목 심볼
    private Long entryTime;            // 진입 주문 전송 시각 (epoch ms, 펀딩비 합산 시작점)
    private BigDecimal entryFee;       // 진입 수수료 (USDT, 순손익 계산용)
}
