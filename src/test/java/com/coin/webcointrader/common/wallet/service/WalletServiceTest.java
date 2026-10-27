package com.coin.webcointrader.common.wallet.service;

import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.account.client.ExchangeAccountClient;
import com.coin.webcointrader.common.ExchangeClientRegistry;
import com.coin.webcointrader.common.account.dto.WalletBalanceDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * 지갑 잔고 서비스 단위 테스트.
 * 거래소 타입별 계정 클라이언트로 위임하는지 검증한다. (응답 변환은 각 거래소 클라이언트 테스트에서 검증)
 */
@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    @InjectMocks
    private WalletService walletService;

    @Mock
    private ExchangeClientRegistry registry;

    @Mock
    private ExchangeAccountClient bybitAccount;

    @Test
    @DisplayName("getWalletBalance: 거래소 타입의 계정 클라이언트 잔고를 그대로 반환한다")
    void getWalletBalance_delegatesToExchange() {
        WalletBalanceDTO balance = WalletBalanceDTO.builder().totalEquity("100").build();
        given(registry.account(ExchangeType.BYBIT)).willReturn(bybitAccount);
        given(bybitAccount.getWalletBalance(1L)).willReturn(balance);

        assertThat(walletService.getWalletBalance(1L, ExchangeType.BYBIT)).isSameAs(balance);
    }

    @Test
    @DisplayName("getWalletBalance: 거래소 조회 실패 예외는 그대로 전파한다")
    void getWalletBalance_propagatesFailure() {
        given(registry.account(ExchangeType.BYBIT)).willReturn(bybitAccount);
        given(bybitAccount.getWalletBalance(1L))
                .willThrow(new CustomException(ExceptionMessage.GET_WALLET_BALANCE_FAILED, "Invalid API key"));

        assertThatThrownBy(() -> walletService.getWalletBalance(1L, ExchangeType.BYBIT))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("Invalid API key");
    }
}
