package com.coin.webcointrader.common.login.service;

import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.login.dto.ExchangeKeyDTO;
import com.coin.webcointrader.common.login.dto.ExchangeKeyStatusDTO;
import com.coin.webcointrader.common.login.entity.UserExchangeKey;
import com.coin.webcointrader.common.login.repository.UserExchangeKeyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

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

    /**
     * 지원 거래소에 대해 사용자의 API Key 등록 상태를 조회한다. (키 값은 포함하지 않음)
     * 레지스트리를 직접 의존하면 순환(계정 클라이언트 → 이 서비스)이라 지원 거래소 목록은 호출자가 넘긴다.
     *
     * @param userId        사용자 ID
     * @param exchangeTypes 지원 거래소 목록 (ExchangeClientRegistry.supportedTypes)
     * @return 전달 순서대로 등록 여부·최종 수정일시 목록 (미등록 거래소도 포함)
     */
    public List<ExchangeKeyStatusDTO> getKeyStatuses(Long userId, List<ExchangeType> exchangeTypes) {
        Map<ExchangeType, UserExchangeKey> keys = userExchangeKeyRepository.findByUserId(userId).stream()
                .collect(Collectors.toMap(UserExchangeKey::getExchangeType, Function.identity()));

        return exchangeTypes.stream()
                .map(type -> {
                    UserExchangeKey key = keys.get(type);
                    // 키가 없는 거래소는 미등록(수정일시 null)
                    return ExchangeKeyStatusDTO.builder()
                            .exchangeType(type)
                            .registered(key != null)
                            .updatedAt(key != null ? key.getUpdatedAt() : null)
                            .build();
                })
                .toList();
    }
}
