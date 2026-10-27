package com.coin.webcointrader.common.trade.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * 거래소 공통 포지션 청산 손익.
 * closedPnl = 매매차익 - 진입 수수료 - 청산 수수료 - 펀딩비
 * (펀딩비를 주지 않는 거래소는 각 구현체가 구성값으로 역산해 채운다)
 */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
public class ClosedPnlDTO {
    private String orderId;     // 청산 주문 ID
    private String closedPnl;   // 실현 손익 (수수료·펀딩비 반영)
    private String fundingFee;  // 펀딩비 (양수: 지급, 음수: 수취)
}
