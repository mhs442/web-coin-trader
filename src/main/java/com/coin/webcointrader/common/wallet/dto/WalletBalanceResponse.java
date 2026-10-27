package com.coin.webcointrader.common.wallet.dto;

import com.coin.webcointrader.common.account.dto.WalletBalanceDTO;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 지갑 잔고 조회 응답. (거래소 중립, USDT 기준)
 */
@Getter
@AllArgsConstructor
public class WalletBalanceResponse {
    private String totalEquity;      // 총 자산 (미실현 손익 포함, 조회 불가 시 null)
    private String walletBalance;    // USDT 지갑 잔고
    private String availableBalance; // 출금·주문 가능 잔고
    private String currency;         // 잔고 단위 (예: USDT)

    public static WalletBalanceResponse from(WalletBalanceDTO balance) {
        return new WalletBalanceResponse(balance.getTotalEquity(), balance.getWalletBalance(),
                balance.getAvailableBalance(), balance.getCurrency());
    }
}
