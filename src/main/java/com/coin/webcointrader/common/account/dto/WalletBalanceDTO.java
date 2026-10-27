package com.coin.webcointrader.common.account.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * 거래소 공통 지갑 잔고. (USDT 기준)
 */
@Getter
@SuperBuilder
@NoArgsConstructor
public class WalletBalanceDTO {
    private String totalEquity;      // 총 자산 (미실현 손익 포함)
    private String walletBalance;    // USDT 지갑 잔고
    private String availableBalance; // 출금·주문 가능 잔고
    private String currency;         // 잔고 단위 (예: USDT, 거래소 구현이 채움)
}
