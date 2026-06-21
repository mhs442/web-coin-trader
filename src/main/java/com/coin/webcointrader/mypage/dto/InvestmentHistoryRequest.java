package com.coin.webcointrader.mypage.dto;

import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

@Getter @Setter
public class InvestmentHistoryRequest {
    private String symbol;
    // 기본 1주일 전부터
    @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime startDate = LocalDateTime.now().minusDays(7).toLocalDate().atStartOfDay();
    // 현재시간까지
    @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime endDate = LocalDateTime.now();
    private String sort = "desc";
    private int page = 0;
    private int size = 20;
    private String mode = "main";   // 거래 모드 ("main" 또는 "sim")
}
