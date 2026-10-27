package com.coin.webcointrader.common.autotrade.util;

import com.coin.webcointrader.common.enums.Side;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class TradeCalculatorTest {

    @Nested
    @DisplayName("resolveRoiRate (투자금 기준 비율 결정)")
    class ResolveRoiRateTest {

        @Test
        @DisplayName("입력값이 있으면 그대로 투자금 기준 비율로 사용한다")
        void returnsInputAsIs() {
            assertThat(TradeCalculator.resolveRoiRate(new BigDecimal("50"), 10))
                    .isEqualByComparingTo("50");
        }

        @Test
        @DisplayName("미입력 시 가격 8% 기본값을 투자금 기준으로 환산한다 (8 × 레버리지)")
        void nullUsesDefaultPriceRate() {
            assertThat(TradeCalculator.resolveRoiRate(null, 10)).isEqualByComparingTo("80");
        }
    }

    @Nested
    @DisplayName("calcTpPrice / calcSlPrice (투자금 기준 → 가격)")
    class AppTpSlPriceTest {

        @Test
        @DisplayName("LONG 10배, 익절 50% / 손절 30% → 가격 +5% / -3%")
        void longRoiToPrice() {
            BigDecimal entry = new BigDecimal("50000");
            assertThat(TradeCalculator.calcTpPrice(Side.LONG, entry, new BigDecimal("50"), 10))
                    .isEqualByComparingTo("52500");
            assertThat(TradeCalculator.calcSlPrice(Side.LONG, entry, new BigDecimal("30"), 10))
                    .isEqualByComparingTo("48500");
        }

        @Test
        @DisplayName("SHORT 10배, 익절 50% / 손절 30% → 가격 -5% / +3%")
        void shortRoiToPrice() {
            BigDecimal entry = new BigDecimal("50000");
            assertThat(TradeCalculator.calcTpPrice(Side.SHORT, entry, new BigDecimal("50"), 10))
                    .isEqualByComparingTo("47500");
            assertThat(TradeCalculator.calcSlPrice(Side.SHORT, entry, new BigDecimal("30"), 10))
                    .isEqualByComparingTo("51500");
        }
    }

    @Nested
    @DisplayName("calcExchangeTpPrice / calcExchangeSlPrice (거래소 안전망 가격)")
    class ExchangeTpSlPriceTest {

        @Test
        @DisplayName("LONG: 앱 기준보다 가격 0.5%p 바깥, tickSize 단위로 진입가에서 먼 쪽으로 반올림")
        void longBufferAndRoundOutward() {
            // 1배, 10% → 거래소 10.5% : TP 100.37×1.105=110.90885 → 올림 110.95 / SL 100.37×0.895=89.83115 → 내림 89.80
            BigDecimal base = new BigDecimal("100.37");
            BigDecimal tick = new BigDecimal("0.05");
            assertThat(TradeCalculator.calcExchangeTpPrice(Side.LONG, base, new BigDecimal("10"), 1, tick))
                    .isEqualByComparingTo("110.95");
            assertThat(TradeCalculator.calcExchangeSlPrice(Side.LONG, base, new BigDecimal("10"), 1, tick))
                    .isEqualByComparingTo("89.80");
        }

        @Test
        @DisplayName("SHORT: TP는 내림, SL은 올림 (진입가에서 먼 쪽)")
        void shortBufferAndRoundOutward() {
            BigDecimal base = new BigDecimal("100.37");
            BigDecimal tick = new BigDecimal("0.05");
            assertThat(TradeCalculator.calcExchangeTpPrice(Side.SHORT, base, new BigDecimal("10"), 1, tick))
                    .isEqualByComparingTo("89.80");
            assertThat(TradeCalculator.calcExchangeSlPrice(Side.SHORT, base, new BigDecimal("10"), 1, tick))
                    .isEqualByComparingTo("110.95");
        }

        @Test
        @DisplayName("버퍼는 레버리지와 무관하게 가격 기준 0.5%p — 10배, 손절 80%(가격 8%) → 거래소 8.5%")
        void bufferIsPriceBased() {
            BigDecimal base = new BigDecimal("50000");
            BigDecimal tick = new BigDecimal("0.10");
            assertThat(TradeCalculator.calcExchangeSlPrice(Side.LONG, base, new BigDecimal("80"), 10, tick))
                    .isEqualByComparingTo("45750");
            assertThat(TradeCalculator.calcExchangeTpPrice(Side.LONG, base, new BigDecimal("80"), 10, tick))
                    .isEqualByComparingTo("54250");
        }

        @Test
        @DisplayName("SHORT 익절가가 0 이하가 되면 null (거래소 익절 미설정)")
        void shortTpBelowZeroReturnsNull() {
            // 1배, 익절 200% → 가격 -200.5% → 음수
            assertThat(TradeCalculator.calcExchangeTpPrice(Side.SHORT, new BigDecimal("100"),
                    new BigDecimal("200"), 1, new BigDecimal("0.01"))).isNull();
        }
    }

    @Nested
    @DisplayName("maxStopLossRate / isStopLossWithinLimit (강제청산 한도)")
    class StopLossLimitTest {

        @Test
        @DisplayName("손절률 최대 = 100 - 1.5 × 레버리지")
        void maxByLeverage() {
            assertThat(TradeCalculator.maxStopLossRate(1)).isEqualByComparingTo("98.5");
            assertThat(TradeCalculator.maxStopLossRate(10)).isEqualByComparingTo("85");
            assertThat(TradeCalculator.maxStopLossRate(50)).isEqualByComparingTo("25");
            assertThat(TradeCalculator.maxStopLossRate(67)).isEqualByComparingTo("-0.5");
        }

        @Test
        @DisplayName("한도 이하면 true, 초과면 false")
        void withinLimit() {
            assertThat(TradeCalculator.isStopLossWithinLimit(new BigDecimal("85"), 10)).isTrue();
            assertThat(TradeCalculator.isStopLossWithinLimit(new BigDecimal("85.1"), 10)).isFalse();
        }

        @Test
        @DisplayName("미입력 기본값(가격 8%)은 10배까지 허용, 11배부터 초과")
        void defaultRateLimit() {
            assertThat(TradeCalculator.isStopLossWithinLimit(TradeCalculator.resolveRoiRate(null, 10), 10)).isTrue();
            assertThat(TradeCalculator.isStopLossWithinLimit(TradeCalculator.resolveRoiRate(null, 11), 11)).isFalse();
        }
    }

    @Nested
    @DisplayName("calcFee (거래소 수수료율 적용)")
    class CalcFeeTest {

        @Test
        @DisplayName("체결가 × 수량 × 수수료율을 소수 4자리로 반올림한다")
        void appliesGivenFeeRate() {
            // 50000 × 0.002 × 0.00055 = 0.055
            assertThat(TradeCalculator.calcFee(new BigDecimal("50000"), new BigDecimal("0.002"), new BigDecimal("0.00055")))
                    .isEqualByComparingTo("0.055");
            // 55600 × 0.001 × 0.001 = 0.0556
            assertThat(TradeCalculator.calcFee(new BigDecimal("55600"), new BigDecimal("0.001"), new BigDecimal("0.001")))
                    .isEqualByComparingTo("0.0556");
        }
    }
}
