package com.coin.webcointrader.autotrade.util;

import com.coin.webcointrader.common.enums.Side;

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

    // 익절/손절률 미입력 시 기본값: 가격 변동 기준 8%
    public static final BigDecimal DEFAULT_PRICE_RATE = new BigDecimal("8");

    // 거래소 안전망 TP/SL 버퍼: 앱 TP/SL보다 가격 기준 0.5%p 바깥 (앱이 먼저 청산하도록)
    public static final BigDecimal EXCHANGE_TPSL_BUFFER_RATE = new BigDecimal("0.5");

    // 강제청산 여유분: 유지증거금률(종목·구간별 상이)을 보수적으로 가격 1%p로 근사
    public static final BigDecimal LIQUIDATION_MARGIN_RATE = BigDecimal.ONE;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private TradeCalculator() {}

    /**
     * 익절/손절률(투자금 기준 %)을 결정한다.
     * 입력값이 있으면 그대로, 미입력이면 가격 8% 기본값을 투자금 기준으로 환산(8 × leverage)한다.
     *
     * @param rate     패턴에 설정된 비율 (%, 투자금 기준, null 허용)
     * @param leverage 레버리지
     * @return 투자금 기준 비율 (%)
     */
    public static BigDecimal resolveRoiRate(BigDecimal rate, int leverage) {
        if (rate != null) {
            return rate;
        }
        return DEFAULT_PRICE_RATE.multiply(BigDecimal.valueOf(leverage));
    }

    /**
     * 거래소 안전망 익절가를 계산한다.
     * 가격 변동률 = rate / leverage + 0.5%p, tickSize 단위로 진입가에서 먼 쪽으로 맞춘다.
     *
     * @param side      포지션 방향 (LONG/SHORT)
     * @param basePrice 기준가 (진입 주문 시점 현재가)
     * @param rate      익절 비율 (%, 투자금 기준 — resolveRoiRate 결과)
     * @param leverage  레버리지
     * @param tickSize  최소 가격 단위
     * @return 거래소 익절가, 0 이하가 되면 null (SHORT 고배율 익절 등 설정 불가)
     */
    public static BigDecimal calcExchangeTpPrice(Side side, BigDecimal basePrice, BigDecimal rate,
                                                 int leverage, BigDecimal tickSize) {
        BigDecimal price = calcTpPrice(side, basePrice, withBuffer(rate, leverage), leverage);
        // LONG 익절은 위로(올림), SHORT 익절은 아래로(내림)
        BigDecimal rounded = roundToTick(price, tickSize, side == Side.LONG ? RoundingMode.CEILING : RoundingMode.FLOOR);
        return rounded.signum() > 0 ? rounded : null;
    }

    /**
     * 거래소 안전망 손절가를 계산한다.
     * 가격 변동률 = rate / leverage + 0.5%p, tickSize 단위로 진입가에서 먼 쪽으로 맞춘다.
     * 강제청산 한도 확인은 호출 측에서 {@link #isStopLossWithinLimit}로 먼저 수행한다.
     *
     * @param side      포지션 방향 (LONG/SHORT)
     * @param basePrice 기준가 (진입 주문 시점 현재가)
     * @param rate      손절 비율 (%, 투자금 기준 — resolveRoiRate 결과)
     * @param leverage  레버리지
     * @param tickSize  최소 가격 단위
     * @return 거래소 손절가
     */
    public static BigDecimal calcExchangeSlPrice(Side side, BigDecimal basePrice, BigDecimal rate,
                                                 int leverage, BigDecimal tickSize) {
        BigDecimal price = calcSlPrice(side, basePrice, withBuffer(rate, leverage), leverage);
        // LONG 손절은 아래로(내림), SHORT 손절은 위로(올림)
        return roundToTick(price, tickSize, side == Side.LONG ? RoundingMode.FLOOR : RoundingMode.CEILING);
    }

    /**
     * 레버리지별 손절률 최대값(투자금 기준 %)을 계산한다.
     * 거래소 손절(가격 rate/leverage + 0.5)이 강제청산(가격 100/leverage - 1)보다 안쪽이어야 하므로
     * rate ≤ 100 - 1.5 × leverage
     *
     * @param leverage 레버리지
     * @return 손절률 최대값 (%), 0 이하면 해당 레버리지에서 설정 불가
     */
    public static BigDecimal maxStopLossRate(int leverage) {
        BigDecimal perLeverage = EXCHANGE_TPSL_BUFFER_RATE.add(LIQUIDATION_MARGIN_RATE); // 1.5
        return HUNDRED.subtract(perLeverage.multiply(BigDecimal.valueOf(leverage)));
    }

    /**
     * 손절률이 강제청산 한도 이내인지 확인한다.
     *
     * @param rate     손절 비율 (%, 투자금 기준 — resolveRoiRate 결과)
     * @param leverage 레버리지
     * @return 한도 이내면 true
     */
    public static boolean isStopLossWithinLimit(BigDecimal rate, int leverage) {
        return rate.compareTo(maxStopLossRate(leverage)) <= 0;
    }

    // 투자금 기준 비율에 가격 버퍼(0.5%p)를 더한다: 가격 0.5%p = 투자금 0.5 × leverage %p
    private static BigDecimal withBuffer(BigDecimal rate, int leverage) {
        return rate.add(EXCHANGE_TPSL_BUFFER_RATE.multiply(BigDecimal.valueOf(leverage)));
    }

    // 가격을 tickSize의 배수로 맞춘다
    private static BigDecimal roundToTick(BigDecimal price, BigDecimal tickSize, RoundingMode mode) {
        return price.divide(tickSize, 0, mode).multiply(tickSize);
    }

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
