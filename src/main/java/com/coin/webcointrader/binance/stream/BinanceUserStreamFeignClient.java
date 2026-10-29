package com.coin.webcointrader.binance.stream;

import com.coin.webcointrader.binance.common.BinanceApiKeyFeignConfig;
import com.coin.webcointrader.binance.stream.dto.BinanceListenKeyResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

/**
 * Binance USDⓈ-M 선물 user data stream 접속 키(listenKey) API 클라이언트.
 * (USER_STREAM — 서명 없이 X-MBX-APIKEY 헤더만, 키는 UserApiKeyContext 에서 읽음)
 * listenKey 는 계정당 1개이며 생성·연장 후 60분간 유효하다. 오류는 BinanceErrorDecoder 가 BinanceApiException 으로 바꾼다.
 */
@FeignClient(name = "binanceUserStreamClient", url = "${binance.api.url}", configuration = BinanceApiKeyFeignConfig.class)
public interface BinanceUserStreamFeignClient {

    /**
     * Binance POST /fapi/v1/listenKey (USER_STREAM, 가중치 1)
     * 이미 유효한 키가 있으면 같은 키를 돌려주고 유효기간을 60분 연장한다.
     *
     * @return 발급된 listenKey
     */
    @PostMapping("/fapi/v1/listenKey")
    BinanceListenKeyResponse createListenKey();

    /**
     * Binance PUT /fapi/v1/listenKey (USER_STREAM, 가중치 1)
     * 유효기간을 60분 연장한다. 키가 없거나 만료됐으면 -1125 → POST 로 재발급 필요.
     *
     * @return 연장된 listenKey
     */
    @PutMapping("/fapi/v1/listenKey")
    BinanceListenKeyResponse keepAliveListenKey();

    /**
     * Binance DELETE /fapi/v1/listenKey (USER_STREAM, 가중치 1)
     * 키를 폐기해 스트림을 종료한다.
     */
    @DeleteMapping("/fapi/v1/listenKey")
    void closeListenKey();
}
