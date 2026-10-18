package com.coin.webcointrader.login.service;

import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.login.dto.ExchangeKeyDTO;
import com.coin.webcointrader.login.repository.UserExchangeKeyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 거래소 API Key 조회 서비스.
 * 다른 도메인이 UserExchangeKey 엔티티/리포지토리에 직접 접근하지 않도록 DTO 로 변환해 제공한다.
 */
@Service
@RequiredArgsConstructor
public class ExchangeKeyService {

    private final UserExchangeKeyRepository userExchangeKeyRepository;

    /**
     * 사용자의 거래소 API Key 를 조회한다. (복호화하지 않고 암호화된 값 그대로 반환)
     *
     * @param userId       사용자 ID
     * @param exchangeType 거래소 타입
     * @return 암호화된 API Key/Secret 을 담은 DTO
     * @throws CustomException 등록된 키가 없는 경우 (API_KEY_NOT_FOUND)
     */
    public ExchangeKeyDTO findKey(Long userId, ExchangeType exchangeType) {
        return userExchangeKeyRepository.findByUserIdAndExchangeType(userId, exchangeType)
                .map(k -> ExchangeKeyDTO.builder()
                        .userId(k.getUserId())
                        .exchangeType(k.getExchangeType())
                        .apiKey(k.getApiKey())
                        .apiSecret(k.getApiSecret())
                        .build())
                .orElseThrow(() -> new CustomException(ExceptionMessage.API_KEY_NOT_FOUND));
    }
}
