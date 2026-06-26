package com.coin.webcointrader.common.util;

import com.coin.webcointrader.common.entity.Side;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 자동매매 계산 유틸리티.
 * TP/SL 가격, 손익, 수수료 계산 로직을 static 메서드로 제공한다.
 */
public final class TradeCalculator {

    // Bybit Linear(USDT 선물) 시장가 주문 taker 수수료율
    public static final BigDecimal TAKER_FEE_RATE = new BigDecimal("0.00055");

    // SL/TP 기본값 안전 계수: 강제청산 거리(100/leverage)의 80%만 사용
    public static final BigDecimal SL_TP_SAFETY_FACTOR = new BigDecimal("0.8");

    private TradeCalculator() {}

    /**
     * 익절(TP) 가격을 계산한다.
     * LONG: entryPrice × (1 + rate / (100 × leverage))
     * SHORT: entryPrice × (1 - rate / (100 × leverage))
     *
     * @param side       포지션 방향 (LONG/SHORT)
     * @param entryPrice 진입가
     * @param rate       익절 비율 (%, 마진 기준)
     * @param leverage   레버리지
     * @return 익절가
     */
    public static BigDecimal calcTpPrice(Side side, BigDecimal entryPrice, BigDecimal rate, int leverage) {
        BigDecimal divisor = BigDecimal.valueOf(100).multiply(BigDecimal.valueOf(leverage));
        BigDecimal delta = rate.divide(divisor, 6, RoundingMode.HALF_UP);
        if (side == Side.LONG) {
            return entryPrice.multiply(BigDecimal.ONE.add(delta));
        } else {
            return entryPrice.multiply(BigDecimal.ONE.subtract(delta));
        }
    }

    /**
     * 손절(SL) 가격을 계산한다.
     * LONG: entryPrice × (1 - rate / (100 × leverage))
     * SHORT: entryPrice × (1 + rate / (100 × leverage))
     *
     * @param side       포지션 방향 (LONG/SHORT)
     * @param entryPrice 진입가
     * @param rate       손절 비율 (%, 마진 기준)
     * @param leverage   레버리지
     * @return 손절가
     */
    public static BigDecimal calcSlPrice(Side side, BigDecimal entryPrice, BigDecimal rate, int leverage) {
        BigDecimal divisor = BigDecimal.valueOf(100).multiply(BigDecimal.valueOf(leverage));
        BigDecimal delta = rate.divide(divisor, 6, RoundingMode.HALF_UP);
        if (side == Side.LONG) {
            return entryPrice.multiply(BigDecimal.ONE.subtract(delta));
        } else {
            return entryPrice.multiply(BigDecimal.ONE.add(delta));
        }
    }

    /**
     * 총 손익을 계산한다. (수수료 미포함)
     * LONG:  (exitPrice - entryPrice) / entryPrice × margin × leverage
     * SHORT: (entryPrice - exitPrice) / entryPrice × margin × leverage
     *
     * @param side       포지션 방향 (LONG/SHORT)
     * @param entryPrice 진입가
     * @param exitPrice  청산가
     * @param margin     마진 (레버리지 미포함 실투자금)
     * @param leverage   레버리지
     * @return 총 손익 (USDT)
     */
    public static BigDecimal calcGrossProfitLoss(Side side, BigDecimal entryPrice,
                                                  BigDecimal exitPrice, BigDecimal margin, int leverage) {
        BigDecimal leverageBd = BigDecimal.valueOf(leverage);
        BigDecimal ratio;
        if (side == Side.LONG) {
            ratio = exitPrice.subtract(entryPrice).divide(entryPrice, 6, RoundingMode.HALF_UP);
        } else {
            ratio = entryPrice.subtract(exitPrice).divide(entryPrice, 6, RoundingMode.HALF_UP);
        }
        return ratio.multiply(margin).multiply(leverageBd).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * 수수료를 계산한다.
     * fee = executedPrice × qty × TAKER_FEE_RATE
     *
     * @param executedPrice 체결가
     * @param qty           체결 수량
     * @return 수수료 (USDT)
     */
    public static BigDecimal calcFee(BigDecimal executedPrice, BigDecimal qty) {
        return executedPrice.multiply(qty).multiply(TAKER_FEE_RATE).setScale(4, RoundingMode.HALF_UP);
    }
}
