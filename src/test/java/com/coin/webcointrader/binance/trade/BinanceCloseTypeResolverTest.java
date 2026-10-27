package com.coin.webcointrader.binance.trade;

import com.coin.webcointrader.common.enums.PositionCloseType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binance 청산 방식 판정 단위 테스트.
 * 청산 주문의 clientOrderId·origType으로 TP/SL/강제청산/기타를 구분하는지 검증한다.
 */
class BinanceCloseTypeResolverTest {

    @Test
    @DisplayName("강제청산 주문(clientOrderId autoclose-)은 LIQUIDATION")
    void liquidation() {
        assertThat(BinanceCloseTypeResolver.resolve("autoclose-1761530000000", "LIQUIDATION"))
                .isEqualTo(PositionCloseType.LIQUIDATION);
    }

    @Test
    @DisplayName("ADL 주문(clientOrderId adl_autoclose)은 사용자 전략과 무관한 거래소 개입이므로 OTHER")
    void adl() {
        assertThat(BinanceCloseTypeResolver.resolve("adl_autoclose", "MARKET")).isEqualTo(PositionCloseType.OTHER);
    }

    @Test
    @DisplayName("origType TAKE_PROFIT_MARKET 또는 clientOrderId -TP 접미사는 TAKE_PROFIT")
    void takeProfit() {
        assertThat(BinanceCloseTypeResolver.resolve("web_abc", "TAKE_PROFIT_MARKET")).isEqualTo(PositionCloseType.TAKE_PROFIT);
        assertThat(BinanceCloseTypeResolver.resolve("q1-E-1761526800000-TP", "MARKET")).isEqualTo(PositionCloseType.TAKE_PROFIT);
    }

    @Test
    @DisplayName("origType STOP_MARKET 또는 clientOrderId -SL 접미사는 STOP_LOSS")
    void stopLoss() {
        assertThat(BinanceCloseTypeResolver.resolve("web_abc", "STOP_MARKET")).isEqualTo(PositionCloseType.STOP_LOSS);
        assertThat(BinanceCloseTypeResolver.resolve("q1-E-1761526800000-SL", "MARKET")).isEqualTo(PositionCloseType.STOP_LOSS);
    }

    @Test
    @DisplayName("일반 시장가 청산·값 누락은 OTHER")
    void other() {
        assertThat(BinanceCloseTypeResolver.resolve("q1-S-1761530000000", "MARKET")).isEqualTo(PositionCloseType.OTHER);
        assertThat(BinanceCloseTypeResolver.resolve(null, null)).isEqualTo(PositionCloseType.OTHER);
    }
}
