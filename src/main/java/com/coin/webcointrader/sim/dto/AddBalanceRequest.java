package com.coin.webcointrader.sim.dto;

import java.math.BigDecimal;

/**
 * 모의 지갑 금액 추가 요청 DTO.
 */
public class AddBalanceRequest {

    private BigDecimal amount; // 추가할 금액 (USDT)

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
}
