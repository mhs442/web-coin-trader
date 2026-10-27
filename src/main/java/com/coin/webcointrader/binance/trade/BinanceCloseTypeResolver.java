package com.coin.webcointrader.binance.trade;

import com.coin.webcointrader.common.enums.PositionCloseType;

/**
 * Binance 청산 주문 정보로 포지션 청산 방식을 판정한다.
 * REST 재조정(findLastClose)과 이후 user data stream 이 같은 규칙을 쓰도록 한곳에 둔다.
 * (Binance: 강제청산 clientOrderId "autoclose-…"·origType LIQUIDATION, ADL "adl_autoclose",
 *  앱이 건 조건부 주문은 clientAlgoId "{clientOrderId}-TP/-SL")
 * 트리거된 알고 주문의 실제 clientOrderId·origType 값은 실거래로 검증 필요.
 */
public final class BinanceCloseTypeResolver {

    private BinanceCloseTypeResolver() {
    }

    /**
     * 청산 방식을 판정한다.
     *
     * @param clientOrderId 청산 주문의 사용자 지정 ID (null 가능)
     * @param origType      청산 주문의 원 주문 유형 (null 가능)
     * @return 청산 방식 (판정 불가 시 OTHER)
     */
    public static PositionCloseType resolve(String clientOrderId, String origType) {
        String id = clientOrderId != null ? clientOrderId : "";
        String type = origType != null ? origType : "";

        // 강제청산 주문
        if (id.startsWith("autoclose-") || "LIQUIDATION".equals(type)) return PositionCloseType.LIQUIDATION;
        // ADL은 사용자 전략과 무관한 거래소 개입 → 기타
        if (id.startsWith("adl_autoclose")) return PositionCloseType.OTHER;
        // 익절 조건부 주문
        if (id.endsWith("-TP") || "TAKE_PROFIT_MARKET".equals(type) || "TAKE_PROFIT".equals(type)) {
            return PositionCloseType.TAKE_PROFIT;
        }
        // 손절 조건부 주문
        if (id.endsWith("-SL") || "STOP_MARKET".equals(type) || "STOP".equals(type)) {
            return PositionCloseType.STOP_LOSS;
        }
        return PositionCloseType.OTHER;
    }
}
