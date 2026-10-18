package com.coin.webcointrader.login.service;

import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.login.dto.ExchangeKeyDTO;
import com.coin.webcointrader.login.entity.UserExchangeKey;
import com.coin.webcointrader.login.repository.UserExchangeKeyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class ExchangeKeyServiceTest {

    @InjectMocks
    private ExchangeKeyService exchangeKeyService;

    @Mock
    private UserExchangeKeyRepository userExchangeKeyRepository;

    @Test
    @DisplayName("findKey: 키가 있으면 암호화된 상태 그대로 DTO로 변환해 반환한다")
    void findKey_success() {
        UserExchangeKey key = new UserExchangeKey();
        key.setUserId(1L);
        key.setExchangeType(ExchangeType.BYBIT);
        key.setApiKey("encKey");
        key.setApiSecret("encSecret");
        given(userExchangeKeyRepository.findByUserIdAndExchangeType(1L, ExchangeType.BYBIT))
                .willReturn(Optional.of(key));

        ExchangeKeyDTO result = exchangeKeyService.findKey(1L, ExchangeType.BYBIT);

        assertThat(result.getUserId()).isEqualTo(1L);
        assertThat(result.getExchangeType()).isEqualTo(ExchangeType.BYBIT);
        assertThat(result.getApiKey()).isEqualTo("encKey");
        assertThat(result.getApiSecret()).isEqualTo("encSecret");
    }

    @Test
    @DisplayName("findKey: 키가 없으면 CustomException(API_KEY_NOT_FOUND) 발생")
    void findKey_notFound() {
        given(userExchangeKeyRepository.findByUserIdAndExchangeType(2L, ExchangeType.BYBIT))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> exchangeKeyService.findKey(2L, ExchangeType.BYBIT))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining(ExceptionMessage.API_KEY_NOT_FOUND.getMessage());
    }
}
