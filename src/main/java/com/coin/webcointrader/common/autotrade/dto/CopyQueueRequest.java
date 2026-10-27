package com.coin.webcointrader.common.autotrade.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 큐 복사 요청 DTO.
 * 복사 대상 심볼, 거래 모드, 대상 거래소를 전달한다.
 */
@Getter
@Setter
public class CopyQueueRequest {
    private String symbol;    // 복사될 대상 코인 심볼
    private String mode;      // 거래 모드 ("main" 또는 "sim")
    private String exchangeType; // 복사 대상 거래소 (없으면 원본 큐의 거래소)
}
