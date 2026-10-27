package com.coin.webcointrader.common.trade.dto;

import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.enums.PositionCloseType;
import lombok.Builder;
import lombok.Getter;

/**
 * 거래소 private 스트림에서 감지한 포지션 청산 체결.
 * 주문 1건의 마지막 청산 체결마다 1회 전달되며, 앱이 보낸 주문인지는 수신부에서 orderLinkId로 판단한다.
 */
@Getter
@Builder
public class PositionCloseDTO {
    private final Long userId;                 // 체결이 발생한 사용자 ID
    private final ExchangeType exchangeType;   // 거래소 타입
    private final String symbol;               // 심볼 (예: "BTCUSDT")
    private final String orderId;              // 청산 주문 ID (실체결·실손익 조회 키)
    private final String orderLinkId;          // 사용자 지정 주문 ID (거래소 TP/SL·수동 청산은 빈 값일 수 있음)
    private final String execPrice;            // 마지막 체결가 (실체결 조회 실패 시 fallback)
    private final PositionCloseType closeType; // 청산 방식
}
