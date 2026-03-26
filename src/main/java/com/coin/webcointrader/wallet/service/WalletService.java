package com.coin.webcointrader.wallet.service;

import com.coin.webcointrader.common.client.account.AccountClient;
import com.coin.webcointrader.common.dto.response.GetWalletBalanceResponse;
import com.coin.webcointrader.common.entity.User;
import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.util.AesEncryptor;
import com.coin.webcointrader.common.util.UserApiKeyContext;
import com.coin.webcointrader.login.repository.LoginRepository;
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
    private final LoginRepository loginRepository;
    private final AesEncryptor aesEncryptor;

    /**
     * 사용자의 UTA 지갑 잔고를 조회한다.
     *
     * @param userId 사용자 ID
     * @return 지갑 잔고 응답 (totalEquity, totalWalletBalance, 코인별 잔고 포함)
     * @throws CustomException 사용자를 찾을 수 없거나 API 호출 실패 시
     */
    public GetWalletBalanceResponse getWalletBalance(Long userId) {
        User user = loginRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ExceptionMessage.USER_NOT_FOUND));

        UserApiKeyContext.set(
                aesEncryptor.decrypt(user.getApiKey()),
                aesEncryptor.decrypt(user.getApiSecret())
        );

        try {
            return accountClient.getWalletBalance("UNIFIED").getBody();
        } finally {
            UserApiKeyContext.clear();
        }
    }
}
