package com.coin.webcointrader.binance.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.codec.ErrorDecoder;
import org.springframework.context.annotation.Bean;

/**
 * Binance 공개(NONE) 엔드포인트용 Feign 설정. (시세 클라이언트에 적용)
 * 서명·키 헤더 없이 에러 응답 변환과 사용 가중치 감시만 적용한다.
 */
public class BinancePublicFeignConfig {

    @Bean
    public ErrorDecoder binanceErrorDecoder(ObjectMapper objectMapper) {
        return new BinanceErrorDecoder(objectMapper);
    }

    @Bean
    public BinanceWeightMonitor binanceWeightMonitor() {
        return new BinanceWeightMonitor();
    }
}
