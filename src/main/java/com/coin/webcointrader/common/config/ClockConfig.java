package com.coin.webcointrader.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 현재 시각 제공용 Clock 빈 설정.
 * 시간 의존 로직(캐시 유효 시간 등)을 테스트에서 제어할 수 있도록 주입받아 사용한다.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
