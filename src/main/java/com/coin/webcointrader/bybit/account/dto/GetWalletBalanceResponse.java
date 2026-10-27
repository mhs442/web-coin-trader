package com.coin.webcointrader.bybit.account.dto;

import com.coin.webcointrader.bybit.common.BybitMasterDTO;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter @Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class GetWalletBalanceResponse extends BybitMasterDTO {
    private Result result;      // 지갑 잔고 조회 결과

    @Getter @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Result {
        private List<AccountInfo> list;         // 계좌 목록

        @Getter @Setter
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class AccountInfo {
            private String accountType;         // 계좌 유형 (예: "UNIFIED")
            private String totalEquity;         // 총 자산 평가액 (USDT 환산)
            private String totalWalletBalance;  // 총 지갑 잔고 (USDT 환산)
            private List<CoinInfo> coin;        // 보유 코인 목록

            @Getter @Setter
            @JsonIgnoreProperties(ignoreUnknown = true)
            public static class CoinInfo {
                private String coin;                    // 코인 심볼 (예: "USDT", "BTC")
                private String walletBalance;           // 지갑 잔고
                private String equity;                  // 자산 평가액 (미실현 손익 포함)
                private String totalPositionIM;         // 포지션 개시 증거금 합계 (+ 청산 수수료 선점분)
                private String totalOrderIM;            // 미체결 주문 선점 증거금
                private String locked;                  // 현물 미체결 주문으로 잠긴 금액
                private String bonus;                   // 보너스 (빈 문자열 가능)
            }
        }
    }
}
