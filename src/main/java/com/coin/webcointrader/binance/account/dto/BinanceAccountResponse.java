package com.coin.webcointrader.binance.account.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Binance GET /fapi/v3/account 응답. (잔고 조회에 필요한 자산 정보만 매핑)
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceAccountResponse {

    private List<Asset> assets;  // 자산별 계정 정보

    /**
     * 자산(코인)별 잔고.
     */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Asset {
        private String asset;             // 자산 이름 (예: USDT)
        private String walletBalance;     // 지갑 잔고 (미실현 손익 제외)
        private String unrealizedProfit;  // 미실현 손익
        private String marginBalance;     // 마진 잔고 (지갑 잔고 + 미실현 손익)
        private String availableBalance;  // 주문 가능 잔고 (포지션·주문 증거금 제외)
    }
}
