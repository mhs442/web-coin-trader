package com.coin.webcointrader.common.login.service;

import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.login.dto.ExchangeKeyDTO;
import com.coin.webcointrader.common.login.dto.ExchangeKeyStatusDTO;
import com.coin.webcointrader.common.login.entity.UserExchangeKey;
import com.coin.webcointrader.common.login.repository.UserExchangeKeyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

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

    @Test
    @DisplayName("getKeyStatuses: 전달한 지원 거래소에 대해 등록 여부와 수정일시를 반환한다")
    void getKeyStatuses_registered() {
        UserExchangeKey key = new UserExchangeKey();
        key.setUserId(1L);
        key.setExchangeType(ExchangeType.BYBIT);
        LocalDateTime updatedAt = LocalDateTime.of(2026, 10, 24, 10, 0);
        ReflectionTestUtils.setField(key, "updatedAt", updatedAt);
        given(userExchangeKeyRepository.findByUserId(1L)).willReturn(List.of(key));

        List<ExchangeKeyStatusDTO> result = exchangeKeyService.getKeyStatuses(1L, List.of(ExchangeType.BYBIT));

        assertThat(result).hasSize(1);
        ExchangeKeyStatusDTO bybit = result.get(0);
        assertThat(bybit.getExchangeType()).isEqualTo(ExchangeType.BYBIT);
        assertThat(bybit.isRegistered()).isTrue();
        assertThat(bybit.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    @DisplayName("getKeyStatuses: 키가 없는 거래소는 미등록으로 반환한다")
    void getKeyStatuses_notRegistered() {
        given(userExchangeKeyRepository.findByUserId(1L)).willReturn(List.of());

        List<ExchangeKeyStatusDTO> result = exchangeKeyService.getKeyStatuses(1L, List.of(ExchangeType.BYBIT));

        assertThat(result).hasSize(1).allSatisfy(status -> {
            assertThat(status.isRegistered()).isFalse();
            assertThat(status.getUpdatedAt()).isNull();
        });
    }
}
