package com.coin.webcointrader.common.client.account;

import com.coin.webcointrader.common.dto.response.GetWalletBalanceResponse;
import com.coin.webcointrader.common.config.BybitFeignConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Bybit 계좌 정보 API 클라이언트.
 * HMAC-SHA256 서명 인증이 필요한 계좌 관련 엔드포인트를 제공한다.
 */
@FeignClient(name = "accountClient", url = "${bybit.api.url}", configuration = BybitFeignConfig.class)
public interface AccountClient {

    /**
     * Bybit GET /v5/account/wallet-balance
     * 지갑 잔고 및 보유 코인별 자산 정보를 조회한다.
     *
     * @param accountType 계좌 유형 (예: "UNIFIED", "CONTRACT")
     * @return 지갑 잔고 응답 (totalEquity, totalWalletBalance, 코인별 잔고 포함)
     */
    @GetMapping("/v5/account/wallet-balance")
    ResponseEntity<GetWalletBalanceResponse> getWalletBalance(
            @RequestParam("accountType") String accountType
    );
}
