package com.coin.webcointrader.mypage.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter @Setter
public class TradeHistoryRequest {
    private String symbol;
    private LocalDateTime startDate;
    private LocalDateTime endDate;
    private String sort = "desc";
    private int page = 0;
    private int size = 20;
}
