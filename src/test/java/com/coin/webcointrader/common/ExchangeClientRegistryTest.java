package com.coin.webcointrader.common;

import com.coin.webcointrader.common.account.client.ExchangeAccountClient;
import com.coin.webcointrader.common.market.client.ExchangeMarketClient;
import com.coin.webcointrader.common.trade.client.ExchangeTradeClient;
import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.exception.CustomException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 거래소 클라이언트 레지스트리 조회 단위 테스트.
 */
class ExchangeClientRegistryTest {

    @Test
    @DisplayName("market/trade/account - 거래소 타입별 구현체 반환")
    void lookupByExchangeType() {
        ExchangeMarketClient market = mock(ExchangeMarketClient.class);
        ExchangeTradeClient trade = mock(ExchangeTradeClient.class);
        ExchangeAccountClient account = mock(ExchangeAccountClient.class);
        given(market.getExchangeType()).willReturn(ExchangeType.BYBIT);
        given(trade.getExchangeType()).willReturn(ExchangeType.BYBIT);
        given(account.getExchangeType()).willReturn(ExchangeType.BYBIT);

        ExchangeClientRegistry registry = new ExchangeClientRegistry(
                List.of(market), List.of(trade), List.of(account));

        assertThat(registry.market(ExchangeType.BYBIT)).isSameAs(market);
        assertThat(registry.trade(ExchangeType.BYBIT)).isSameAs(trade);
        assertThat(registry.account(ExchangeType.BYBIT)).isSameAs(account);
        assertThat(registry.markets()).containsExactly(market);
        assertThat(registry.trades()).containsExactly(trade);
    }

    @Test
    @DisplayName("supportedTypes - 시세·거래·계정 클라이언트가 모두 등록된 거래소만 선언 순서대로 반환한다")
    void supportedTypes_onlyFullyRegistered() {
        // BINANCE 는 계정 클라이언트가 없음 (구현 중인 거래소 흉내)
        ExchangeMarketClient bybitMarket = mock(ExchangeMarketClient.class);
        ExchangeMarketClient binanceMarket = mock(ExchangeMarketClient.class);
        ExchangeTradeClient bybitTrade = mock(ExchangeTradeClient.class);
        ExchangeTradeClient binanceTrade = mock(ExchangeTradeClient.class);
        ExchangeAccountClient bybitAccount = mock(ExchangeAccountClient.class);
        given(bybitMarket.getExchangeType()).willReturn(ExchangeType.BYBIT);
        given(binanceMarket.getExchangeType()).willReturn(ExchangeType.BINANCE);
        given(bybitTrade.getExchangeType()).willReturn(ExchangeType.BYBIT);
        given(binanceTrade.getExchangeType()).willReturn(ExchangeType.BINANCE);
        given(bybitAccount.getExchangeType()).willReturn(ExchangeType.BYBIT);

        ExchangeClientRegistry registry = new ExchangeClientRegistry(
                List.of(binanceMarket, bybitMarket), List.of(binanceTrade, bybitTrade), List.of(bybitAccount));

        assertThat(registry.supportedTypes()).containsExactly(ExchangeType.BYBIT);
    }

    @Test
    @DisplayName("market/trade/account - 등록되지 않은 거래소면 EXCHANGE_NOT_SUPPORTED")
    void unsupportedExchange() {
        ExchangeClientRegistry registry = new ExchangeClientRegistry(List.of(), List.of(), List.of());

        assertThatThrownBy(() -> registry.market(ExchangeType.BYBIT))
                .isInstanceOf(CustomException.class)
                .extracting("exceptionMessage").isEqualTo(ExceptionMessage.EXCHANGE_NOT_SUPPORTED);
        assertThatThrownBy(() -> registry.trade(ExchangeType.BYBIT)).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> registry.account(ExchangeType.BYBIT)).isInstanceOf(CustomException.class);
    }
}
