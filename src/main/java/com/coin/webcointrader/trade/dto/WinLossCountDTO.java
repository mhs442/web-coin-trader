package com.coin.webcointrader.trade.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 기간 내 승패 카운트. */
@Getter
@AllArgsConstructor
public class WinLossCountDTO {
    private final long winCount;    // 수익(손익 > 0) 건수
    private final long lossCount;   // 손실(손익 <= 0) 건수
    private final long totalCount;  // 전체 건수
}
