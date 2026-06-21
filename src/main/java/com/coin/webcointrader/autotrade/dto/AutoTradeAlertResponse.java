package com.coin.webcointrader.autotrade.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 자동매매 큐 비활성화 등 즉시 알림이 필요한 이벤트 응답.
 * 프론트가 STOMP /topic/autotrade.alert.{symbol} 토픽을 구독해 실시간 alert으로 표시한다.
 */
@Getter
@Builder
public class AutoTradeAlertResponse {
    private Long queueId;          // 비활성화된 큐 ID
    private String symbol;         // 코인 심볼
    private String reason;         // 비활성화 사유 (사용자에게 표시될 메시지)
    private String level;          // 알림 레벨 ("ERROR" / "WARN" / "INFO")
    private String occurredAt;     // 발생 시각 (HH:mm:ss)
}
