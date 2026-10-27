package com.coin.webcointrader.common.login.service;

import com.coin.webcointrader.common.autotrade.service.AutoTradeService;
import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.enums.TradeMode;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.ExchangeClientRegistry;
import com.coin.webcointrader.common.util.AesEncryptor;
import com.coin.webcointrader.common.login.entity.UserExchangeKey;
import com.coin.webcointrader.common.login.repository.UserExchangeKeyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 거래소 API Key 등록·교체 서비스.
 * 회원가입과 마이페이지가 같은 검증·암호화·저장 절차를 사용한다.
 * 거래소 계정 클라이언트가 {@link ExchangeKeyService}에 의존하므로, 순환 의존을 피하려고 조회 서비스와 분리한다.
 */
@Service
@RequiredArgsConstructor
public class ExchangeKeyRegisterService {

    private final UserExchangeKeyRepository userExchangeKeyRepository;
    private final ExchangeClientRegistry registry;
    private final AesEncryptor aesEncryptor;
    private final AutoTradeService autoTradeService;

    /**
     * 거래소 API Key를 검증한 뒤 AES 암호화해 저장한다. 이미 등록돼 있으면 같은 행의 값을 교체한다.
     *
     * @param userId       사용자 ID
     * @param exchangeType 거래소 타입
     * @param apiKey       평문 API Key
     * @param apiSecret    평문 API Secret
     * @throws CustomException 해당 거래소 실전 자동매매 실행 중(EXCHANGE_KEY_CHANGE_BLOCKED),
     *                         거래소 검증 실패(INVALID_API_KEY), 미지원 거래소(EXCHANGE_NOT_SUPPORTED)
     */
    @Transactional
    public void saveKey(Long userId, ExchangeType exchangeType, String apiKey, String apiSecret) {
        // 1. 실전 자동매매 중에는 교체 불가 — 연결된 private 스트림·주문이 이전 키(계정)를 계속 사용하므로
        // [사용자 정책 2026-10-24] 실행 중 교체는 막고 중지 후 교체하도록 안내 (스트림 재연결 방식은 채택하지 않음)
        if (autoTradeService.hasActiveSession(userId, exchangeType, TradeMode.MAIN)) {
            throw new CustomException(ExceptionMessage.EXCHANGE_KEY_CHANGE_BLOCKED);
        }

        // 2. 해당 거래소에 키 유효성 검증
        if (!registry.account(exchangeType).validateApiKey(apiKey, apiSecret)) {
            throw new CustomException(ExceptionMessage.INVALID_API_KEY);
        }

        // 3. 기존 행이 있으면 교체, 없으면 신규 (사용자·거래소당 1행)
        UserExchangeKey key = userExchangeKeyRepository.findByUserIdAndExchangeType(userId, exchangeType)
                .orElseGet(() -> {
                    UserExchangeKey created = new UserExchangeKey();
                    created.setUserId(userId);
                    created.setExchangeType(exchangeType);
                    return created;
                });
        key.setApiKey(aesEncryptor.encrypt(apiKey));
        key.setApiSecret(aesEncryptor.encrypt(apiSecret));
        userExchangeKeyRepository.save(key);
    }
}
