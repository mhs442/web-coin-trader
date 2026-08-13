package com.coin.webcointrader.wallet.service;

import com.coin.webcointrader.common.client.account.AccountClient;
import com.coin.webcointrader.common.dto.response.GetWalletBalanceResponse;
import com.coin.webcointrader.common.entity.UserExchangeKey;
import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.repository.UserExchangeKeyRepository;
import com.coin.webcointrader.common.util.AesEncryptor;
import com.coin.webcointrader.common.util.UserApiKeyContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 지갑 잔고 조회 서비스.
 * Bybit API를 호출하여 사용자의 UTA 계정 잔고를 반환한다.
 */
@Service
@RequiredArgsConstructor
public class WalletService {

    private final AccountClient accountClient;
    private final UserExchangeKeyRepository userExchangeKeyRepository;
    private final AesEncryptor aesEncryptor;

    /**
     * 사용자의 UTA 지갑 잔고를 조회한다.
     * Bybit 응답의 retCode가 "0"이 아니면(서명 오류, 권한 없음 등) 예외를 던진다.
     * retCode 검증 없이 그대로 반환하면 프론트엔드가 실패를 인지하지 못해
     * 이전에 표시된 값(예: 모의 지갑 잔액)이 화면에 그대로 남는 문제가 있었다.
     *
     * @param userId 사용자 ID
     * @return 지갑 잔고 응답 (totalEquity, totalWalletBalance, 코인별 잔고 포함)
     * @throws CustomException 사용자를 찾을 수 없거나(USER_NOT_FOUND), API 호출 실패 시(GET_WALLET_BALANCE_FAILED)
     */
    public GetWalletBalanceResponse getWalletBalance(Long userId) {
        UserExchangeKey key = userExchangeKeyRepository
                .findByUserIdAndExchangeType(userId, ExchangeType.BYBIT)
                .orElseThrow(() -> new CustomException(ExceptionMessage.API_KEY_NOT_FOUND));

        UserApiKeyContext.set(
                aesEncryptor.decrypt(key.getApiKey()),
                aesEncryptor.decrypt(key.getApiSecret())
        );

        try {
            GetWalletBalanceResponse response = accountClient.getWalletBalance("UNIFIED").getBody();

            // Bybit 응답 에러 코드 검증 (HTTP 200이어도 retCode로 실패가 표현될 수 있음)
            if (response == null || !"0".equals(response.getRetCode())) {
                String errorMsg = response != null ? response.getRetMsg() : "응답 없음";
                throw new CustomException(ExceptionMessage.GET_WALLET_BALANCE_FAILED, errorMsg);
            }

            return response;
        } finally {
            UserApiKeyContext.clear();
        }
    }
}
