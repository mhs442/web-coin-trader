package com.coin.webcointrader.common.dto.response;

import com.coin.webcointrader.common.dto.BybitMasterDTO;
import lombok.Getter;

import java.util.List;

@Getter
public class GetWalletBalanceResponse extends BybitMasterDTO {
    private Result result;      // 지갑 잔고 조회 결과

    @Getter
    public static class Result {
        private List<AccountInfo> list;         // 계좌 목록

        @Getter
        public static class AccountInfo {
            private String accountType;         // 계좌 유형 (예: "UNIFIED")
            private String totalEquity;         // 총 자산 평가액 (USDT 환산)
            private String totalWalletBalance;  // 총 지갑 잔고 (USDT 환산)
            private List<CoinInfo> coin;        // 보유 코인 목록

            @Getter
            public static class CoinInfo {
                private String coin;                    // 코인 심볼 (예: "USDT", "BTC")
                private String walletBalance;           // 지갑 잔고
                private String equity;                  // 자산 평가액 (미실현 손익 포함)
                private String availableToWithdraw;     // 출금 가능 금액
            }
        }
    }
}
