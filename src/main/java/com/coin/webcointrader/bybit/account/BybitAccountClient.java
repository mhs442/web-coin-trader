package com.coin.webcointrader.bybit.account;

import com.coin.webcointrader.bybit.account.dto.GetWalletBalanceResponse;
import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.account.client.ExchangeAccountClient;
import com.coin.webcointrader.common.account.dto.WalletBalanceDTO;
import com.coin.webcointrader.common.util.AesEncryptor;
import com.coin.webcointrader.common.util.UserApiKeyContext;
import com.coin.webcointrader.common.login.dto.ExchangeKeyDTO;
import com.coin.webcointrader.common.login.service.ExchangeKeyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Bybit 계정 클라이언트.
 * {@link ExchangeAccountClient}의 Bybit 구현체로, UNIFIED(UTA) 지갑 잔고 조회와 API 키 검증을 제공한다.
 */
@Component
@RequiredArgsConstructor
public class BybitAccountClient implements ExchangeAccountClient {

    private static final String ACCOUNT_TYPE = "UNIFIED"; // 조회 대상 계좌 (통합 거래 계좌)
    private static final String QUOTE_COIN = "USDT";      // 잔고 기준 코인

    private final AccountClient accountClient;
    private final BybitApiKeyValidator bybitApiKeyValidator;
    private final ExchangeKeyService exchangeKeyService;
    private final AesEncryptor aesEncryptor;

    @Override
    public ExchangeType getExchangeType() {
        return ExchangeType.BYBIT;
    }

    /**
     * 사용자의 UNIFIED 지갑 잔고를 조회한다.
     * GET /v5/account/wallet-balance?accountType=UNIFIED
     *
     * @param userId 사용자 ID
     * @return 총 자산(totalEquity), USDT 지갑 잔고(walletBalance), USDT 격리 마진 가용 잔고(availableBalance).
     *         UNIFIED 계좌나 USDT 보유 내역이 없으면 해당 값은 null
     * @throws CustomException 키 미등록(API_KEY_NOT_FOUND), 응답 없음·retCode≠0(GET_WALLET_BALANCE_FAILED)
     */
    @Override
    public WalletBalanceDTO getWalletBalance(Long userId) {
        setApiKeyContext(userId);
        try {
            GetWalletBalanceResponse response = accountClient.getWalletBalance(ACCOUNT_TYPE).getBody();

            // HTTP 200이어도 retCode로 실패(서명 오류, 권한 없음 등)가 표현될 수 있음
            if (response == null || !"0".equals(response.getRetCode())) {
                String errorMsg = response != null ? response.getRetMsg() : "응답 없음";
                throw new CustomException(ExceptionMessage.GET_WALLET_BALANCE_FAILED, errorMsg);
            }
            return toWalletBalance(response);
        } finally {
            UserApiKeyContext.clear();
        }
    }

    /**
     * API Key 유효성을 검증한다. (GET /v5/user/query-api)
     *
     * @param apiKey    평문 API Key
     * @param apiSecret 평문 API Secret
     * @return 유효하면 true
     */
    @Override
    public boolean validateApiKey(String apiKey, String apiSecret) {
        return bybitApiKeyValidator.validate(apiKey, apiSecret);
    }

    // ─── 내부 헬퍼 ─────────────────────────────────────

    /**
     * Bybit 지갑 응답을 공통 잔고 DTO로 변환한다.
     * 총 자산·지갑·가용 잔고 모두 USDT 코인 단위 값을 사용한다.
     * 가용 잔고는 격리 마진 공식으로 계산한다. (availableToWithdraw 는 UNIFIED 계좌에서 2025-01-09부터 deprecated)
     */
    private WalletBalanceDTO toWalletBalance(GetWalletBalanceResponse response) {
        List<GetWalletBalanceResponse.Result.AccountInfo> accounts =
                response.getResult() != null ? response.getResult().getList() : null;

        // UNIFIED 계좌가 없으면(미개설) 빈 잔고
        if (accounts == null || accounts.isEmpty()) {
            return WalletBalanceDTO.builder().build();
        }

        GetWalletBalanceResponse.Result.AccountInfo account = accounts.get(0);
        GetWalletBalanceResponse.Result.AccountInfo.CoinInfo usdt = account.getCoin() == null ? null
                : account.getCoin().stream()
                        .filter(c -> QUOTE_COIN.equals(c.getCoin()))
                        .findFirst()
                        .orElse(null);

        return WalletBalanceDTO.builder()
                // [사용자 정책 2026-10-27] 세 값 모두 USDT 코인 기준 (계좌 단위 totalEquity는 USD 합계이고 격리 마진 미적용)
                // USDT 보유 내역이 없으면 코인 단위 잔고는 null
                .totalEquity(usdt != null ? usdt.getEquity() : null)
                .walletBalance(usdt != null ? usdt.getWalletBalance() : null)
                .availableBalance(usdt != null ? calcIsolatedAvailable(usdt) : null)
                .currency(QUOTE_COIN)
                .build();
    }

    /**
     * 격리 마진 가용 잔고 = walletBalance - totalPositionIM - totalOrderIM - locked - bonus (Bybit 문서 공식)
     * 계좌 단위 totalAvailableBalance 는 격리 마진에 적용되지 않아 코인 단위 값으로 계산한다.
     *
     * @param coin USDT 코인 잔고
     * @return 가용 잔고 문자열, 지갑 잔고가 없으면 null
     */
    private String calcIsolatedAvailable(GetWalletBalanceResponse.Result.AccountInfo.CoinInfo coin) {
        if (coin.getWalletBalance() == null || coin.getWalletBalance().isBlank()) {
            return null;
        }
        BigDecimal available = new BigDecimal(coin.getWalletBalance())
                .subtract(toDecimal(coin.getTotalPositionIM()))
                .subtract(toDecimal(coin.getTotalOrderIM()))
                .subtract(toDecimal(coin.getLocked()))
                .subtract(toDecimal(coin.getBonus()));
        return available.stripTrailingZeros().toPlainString();
    }

    /** 빈 문자열·null 은 0 으로 본다. (포트폴리오 마진 등에서 빈 문자열로 내려옴) */
    private static BigDecimal toDecimal(String value) {
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value);
    }

    /**
     * 사용자의 Bybit API Key/Secret을 복호화하여 ThreadLocal 컨텍스트에 설정한다.
     *
     * @param userId 사용자 ID
     */
    private void setApiKeyContext(Long userId) {
        ExchangeKeyDTO key = exchangeKeyService.findKey(userId, ExchangeType.BYBIT);

        UserApiKeyContext.set(
                aesEncryptor.decrypt(key.getApiKey()),
                aesEncryptor.decrypt(key.getApiSecret())
        );
    }
}
