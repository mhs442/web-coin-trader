package com.coin.webcointrader.autotrade.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 큐 복사 요청 DTO.
 * 복사 대상 심볼과 거래 모드를 전달한다.
 */
@Getter
@Setter
public class CopyQueueRequest {
    private String symbol;    // 복사될 대상 코인 심볼
    private String mode;      // 거래 모드 ("main" 또는 "sim")
}
