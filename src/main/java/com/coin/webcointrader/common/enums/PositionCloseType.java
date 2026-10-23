package com.coin.webcointrader.common.enums;

import lombok.Getter;

/**
 * 거래소에서 포지션이 청산된 방식.
 * 앱이 직접 보내지 않은 청산(외부 청산)의 후속 처리를 결정하는 데 사용한다.
 */
@Getter
public enum PositionCloseType {
    TAKE_PROFIT("거래소 익절"),     // 거래소 TP 주문 발동 → 매도와 같이 처리
    STOP_LOSS("거래소 손절"),       // 거래소 SL 주문 발동 → 청산(손절)과 같이 처리
    LIQUIDATION("강제청산"),        // 거래소 강제청산 → 청산(손절)과 같이 처리
    OTHER("수동 청산/ADL");         // 사용자 수동 청산, ADL 등 → 큐 비활성화

    private final String explain; // 로그·알림 표기용 설명

    PositionCloseType(String explain) {
        this.explain = explain;
    }
}
