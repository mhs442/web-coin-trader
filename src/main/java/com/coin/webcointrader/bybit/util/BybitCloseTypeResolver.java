package com.coin.webcointrader.bybit.util;

import com.coin.webcointrader.common.enums.PositionCloseType;

/**
 * Bybit 체결 정보로 포지션 청산 방식을 판정한다.
 * WS 체결 push(외부 청산 실시간 감지)와 REST 체결 조회(놓친 외부 청산 재조정)가 같은 규칙을 쓰도록 한곳에 둔다.
 * (Bybit: stopOrderType TakeProfit/StopLoss/PartialTakeProfit/PartialStopLoss,
 *  createType CreateByTakeProfit/CreateByStopLoss/CreateByLiq/CreateByClosing …)
 */
public final class BybitCloseTypeResolver {

    private BybitCloseTypeResolver() {
    }

    /**
     * 청산 방식을 판정한다.
     *
     * @param execType      체결 유형 (Trade, BustTrade, AdlTrade …)
     * @param stopOrderType 스톱 주문 유형 (null 가능)
     * @param createType    주문 생성 유형 (null 가능)
     * @return 청산 방식 (판정 불가 시 OTHER)
     */
    public static PositionCloseType resolve(String execType, String stopOrderType, String createType) {
        String stop = stopOrderType != null ? stopOrderType : "";
        String create = createType != null ? createType : "";

        // 강제청산 체결 또는 강제청산 주문
        if ("BustTrade".equals(execType) || "CreateByLiq".equals(create)) return PositionCloseType.LIQUIDATION;
        // ADL은 사용자 전략과 무관한 거래소 개입 → 기타
        if ("AdlTrade".equals(execType)) return PositionCloseType.OTHER;
        // 거래소 TP/SL 주문 발동 (부분 TP/SL 포함)
        if (stop.contains("TakeProfit") || create.contains("TakeProfit")) return PositionCloseType.TAKE_PROFIT;
        if (stop.contains("StopLoss") || create.contains("StopLoss")) return PositionCloseType.STOP_LOSS;
        // 그 외(수동 청산 등)
        return PositionCloseType.OTHER;
    }
}
