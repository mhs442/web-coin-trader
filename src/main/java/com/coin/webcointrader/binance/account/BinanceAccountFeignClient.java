package com.coin.webcointrader.binance.account;

import com.coin.webcointrader.binance.account.dto.BinanceAccountResponse;
import com.coin.webcointrader.binance.common.BinanceFeignConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Binance USDⓈ-M 선물 계정 API 클라이언트. (SIGNED — 키는 UserApiKeyContext 에서 읽어 서명)
 * 오류는 HTTP 4XX/5XX + {code,msg} 로 오며 BinanceErrorDecoder 가 BinanceApiException 으로 바꾼다.
 */
@FeignClient(name = "binanceAccountClient", url = "${binance.api.url}", configuration = BinanceFeignConfig.class)
public interface BinanceAccountFeignClient {

    /**
     * Binance GET /fapi/v3/account (USER_DATA, 가중치 5)
     * timestamp·recvWindow·signature 는 서명 인터셉터가 붙인다.
     *
     * @return 계정 정보 (자산별 지갑·마진·가용 잔고)
     */
    @GetMapping("/fapi/v3/account")
    BinanceAccountResponse getAccount();
}
