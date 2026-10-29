package com.coin.webcointrader.binance.common;

import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.util.UserApiKeyContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.RequestInterceptor;
import feign.codec.ErrorDecoder;
import org.springframework.context.annotation.Bean;

/**
 * Binance API 키 전용(USER_STREAM) 엔드포인트용 Feign 설정. (listenKey 클라이언트에 적용)
 * 서명 없이 X-MBX-APIKEY 헤더만 붙인다. (서명 파라미터를 붙여도 거래소가 거부하지는 않지만 명세에 맞춘다)
 */
public class BinanceApiKeyFeignConfig {

    @Bean
    public RequestInterceptor apiKeyRequestInterceptor() {
        return template -> {
            String apiKey = UserApiKeyContext.getApiKey();
            if (apiKey == null) {
                throw new CustomException(ExceptionMessage.INVALID_API_KEY);
            }
            template.header("X-MBX-APIKEY", apiKey);
        };
    }

    @Bean
    public ErrorDecoder binanceErrorDecoder(ObjectMapper objectMapper) {
        return new BinanceErrorDecoder(objectMapper);
    }

    @Bean
    public BinanceWeightMonitor binanceWeightMonitor() {
        return new BinanceWeightMonitor();
    }
}
