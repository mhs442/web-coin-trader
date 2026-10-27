package com.coin.webcointrader.common.login.service;

import com.coin.webcointrader.common.autotrade.service.AutoTradeService;
import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.enums.TradeMode;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.account.client.ExchangeAccountClient;
import com.coin.webcointrader.common.ExchangeClientRegistry;
import com.coin.webcointrader.common.util.AesEncryptor;
import com.coin.webcointrader.common.login.entity.UserExchangeKey;
import com.coin.webcointrader.common.login.repository.UserExchangeKeyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/**
 * 거래소 API Key 등록·교체 서비스 단위 테스트.
 */
@ExtendWith(MockitoExtension.class)
class ExchangeKeyRegisterServiceTest {

    @InjectMocks
    private ExchangeKeyRegisterService exchangeKeyRegisterService;

    @Mock
    private UserExchangeKeyRepository userExchangeKeyRepository;

    @Mock
    private ExchangeClientRegistry registry;

    @Mock
    private ExchangeAccountClient bybitAccount;

    @Mock
    private AesEncryptor aesEncryptor;

    @Mock
    private AutoTradeService autoTradeService;

    @Test
    @DisplayName("saveKey: 키가 없으면 검증 후 암호화해 새로 저장한다")
    void saveKey_new() {
        given(registry.account(ExchangeType.BYBIT)).willReturn(bybitAccount);
        given(bybitAccount.validateApiKey("apiKey", "apiSecret")).willReturn(true);
        given(aesEncryptor.encrypt("apiKey")).willReturn("encKey");
        given(aesEncryptor.encrypt("apiSecret")).willReturn("encSecret");
        given(userExchangeKeyRepository.findByUserIdAndExchangeType(1L, ExchangeType.BYBIT)).willReturn(Optional.empty());

        exchangeKeyRegisterService.saveKey(1L, ExchangeType.BYBIT, "apiKey", "apiSecret");

        then(userExchangeKeyRepository).should().save(argThat(k ->
                k.getUserId().equals(1L)
                && k.getExchangeType() == ExchangeType.BYBIT
                && "encKey".equals(k.getApiKey())
                && "encSecret".equals(k.getApiSecret())));
    }

    @Test
    @DisplayName("saveKey: 키가 있으면 같은 행의 키 값을 교체한다")
    void saveKey_replace() {
        UserExchangeKey existing = new UserExchangeKey();
        existing.setId(10L);
        existing.setUserId(1L);
        existing.setExchangeType(ExchangeType.BYBIT);
        existing.setApiKey("oldEncKey");
        existing.setApiSecret("oldEncSecret");
        given(registry.account(ExchangeType.BYBIT)).willReturn(bybitAccount);
        given(bybitAccount.validateApiKey("newKey", "newSecret")).willReturn(true);
        given(aesEncryptor.encrypt("newKey")).willReturn("newEncKey");
        given(aesEncryptor.encrypt("newSecret")).willReturn("newEncSecret");
        given(userExchangeKeyRepository.findByUserIdAndExchangeType(1L, ExchangeType.BYBIT)).willReturn(Optional.of(existing));

        exchangeKeyRegisterService.saveKey(1L, ExchangeType.BYBIT, "newKey", "newSecret");

        then(userExchangeKeyRepository).should().save(existing);
        assertThat(existing.getId()).isEqualTo(10L);
        assertThat(existing.getApiKey()).isEqualTo("newEncKey");
        assertThat(existing.getApiSecret()).isEqualTo("newEncSecret");
    }

    @Test
    @DisplayName("saveKey: 거래소 검증에 실패하면 저장하지 않고 INVALID_API_KEY")
    void saveKey_invalidKey() {
        given(registry.account(ExchangeType.BYBIT)).willReturn(bybitAccount);
        given(bybitAccount.validateApiKey("badKey", "badSecret")).willReturn(false);

        assertThatThrownBy(() -> exchangeKeyRegisterService.saveKey(1L, ExchangeType.BYBIT, "badKey", "badSecret"))
                .isInstanceOf(CustomException.class)
                .hasMessage(ExceptionMessage.INVALID_API_KEY.getMessage());
        then(userExchangeKeyRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("saveKey: 해당 거래소 실전 자동매매가 실행 중이면 검증·저장 없이 EXCHANGE_KEY_CHANGE_BLOCKED")
    void saveKey_blockedWhileAutoTrading() {
        given(autoTradeService.hasActiveSession(1L, ExchangeType.BYBIT, TradeMode.MAIN)).willReturn(true);

        assertThatThrownBy(() -> exchangeKeyRegisterService.saveKey(1L, ExchangeType.BYBIT, "newKey", "newSecret"))
                .isInstanceOf(CustomException.class)
                .hasMessage(ExceptionMessage.EXCHANGE_KEY_CHANGE_BLOCKED.getMessage());
        then(registry).shouldHaveNoInteractions();
        then(aesEncryptor).should(never()).encrypt(anyString());
        then(userExchangeKeyRepository).should(never()).save(any());
    }
}
