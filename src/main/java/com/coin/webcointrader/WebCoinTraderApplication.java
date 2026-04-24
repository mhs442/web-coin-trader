package com.coin.webcointrader;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@EnableFeignClients
@SpringBootApplication
@EnableScheduling
public class WebCoinTraderApplication {

    public static void main(String[] args) {
        // JVM 기본 타임존을 KST로 고정 (LocalDateTime.now() 등 모든 시간 연산에 적용)
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        SpringApplication.run(WebCoinTraderApplication.class, args);
    }
}