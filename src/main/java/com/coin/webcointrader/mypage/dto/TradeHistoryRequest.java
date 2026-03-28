package com.coin.webcointrader.mypage.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter @Setter
public class TradeHistoryRequest {
    private String symbol;
    // 2022/1/1/00:00:00 부터
    private LocalDateTime startDate = LocalDateTime.of(2022, 1, 1, 0, 0, 0, 0);
    // 현재시간까지
    private LocalDateTime endDate = LocalDateTime.now();
    private String sort = "desc";
    private int page = 0;
    private int size = 20;
    private String mode = "main";   // 거래 모드 ("main" 또는 "sim")
}
