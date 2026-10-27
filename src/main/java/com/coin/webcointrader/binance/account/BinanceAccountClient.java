package com.coin.webcointrader.binance.account;

import com.coin.webcointrader.binance.account.dto.BinanceAccountResponse;
import com.coin.webcointrader.binance.common.BinanceApiException;
import com.coin.webcointrader.common.account.client.ExchangeAccountClient;
import com.coin.webcointrader.common.account.dto.WalletBalanceDTO;
import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.login.dto.ExchangeKeyDTO;
import com.coin.webcointrader.common.login.service.ExchangeKeyService;
import com.coin.webcointrader.common.util.AesEncryptor;
import com.coin.webcointrader.common.util.UserApiKeyContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Binance 계정 클라이언트.
 * {@link ExchangeAccountClient}의 Binance USDⓈ-M 선물 구현체로, USDT 자산 잔고 조회와 API 키 검증을 제공한다.
 */
@Component
@RequiredArgsConstructor
public class BinanceAccountClient implements ExchangeAccountClient {

    private static final String QUOTE_ASSET = "USDT"; // 잔고 기준 자산

    private final BinanceAccountFeignClient feignClient;
    private final ExchangeKeyService exchangeKeyService;
    private final AesEncryptor aesEncryptor;

    @Override
    public ExchangeType getExchangeType() {
        return ExchangeType.BINANCE;
    }

    /**
     * 사용자의 선물 계정 USDT 자산 잔고를 조회한다.
     * GET /fapi/v3/account
     *
     * @param userId 사용자 ID
     * @return 총 자산(marginBalance), 지갑 잔고(walletBalance), 가용 잔고(availableBalance), 단위 USDT.
     *         USDT 자산이 없으면 금액은 null
     * @throws CustomException 키 미등록(API_KEY_NOT_FOUND), Binance 오류 응답·응답 없음(GET_WALLET_BALANCE_FAILED)
     */
    @Override
    public WalletBalanceDTO getWalletBalance(Long userId) {
        ExchangeKeyDTO key = exchangeKeyService.findKey(userId, ExchangeType.BINANCE);
        UserApiKeyContext.set(aesEncryptor.decrypt(key.getApiKey()), aesEncryptor.decrypt(key.getApiSecret()));
        try {
            BinanceAccountResponse response = feignClient.getAccount();
            if (response == null) {
                throw new CustomException(ExceptionMessage.GET_WALLET_BALANCE_FAILED, "응답 없음");
            }
            return toWalletBalance(response);
        } catch (BinanceApiException e) {
            // "[code] msg" 형태 (키 값은 포함되지 않음)
            throw new CustomException(ExceptionMessage.GET_WALLET_BALANCE_FAILED, e.getMessage());
        } finally {
            UserApiKeyContext.clear();
        }
    }

    /**
     * 입력받은 키로 계정 정보를 조회해 API 키 유효성을 검증한다. (GET /fapi/v3/account)
     * [사용자 정책 2026-10-27] 권한(읽기 전용 여부)은 검증하지 않음 — 키 유효성만 확인하고,
     * 권한 부족은 실거래 시 거래소 거부 내용을 거래 히스토리에 기록한다.
     *
     * @param apiKey    평문 API Key
     * @param apiSecret 평문 API Secret
     * @return 조회가 성공하면 true, 키 오류·네트워크 오류 등 실패하면 false
     */
    @Override
    public boolean validateApiKey(String apiKey, String apiSecret) {
        UserApiKeyContext.set(apiKey, apiSecret);
        try {
            return feignClient.getAccount() != null;
        } catch (Exception e) {
            return false;
        } finally {
            UserApiKeyContext.clear();
        }
    }

    // ─── 내부 헬퍼 ─────────────────────────────────────

    /**
     * 계정 응답에서 USDT 자산을 찾아 공통 잔고 DTO로 변환한다.
     * 가용 잔고는 Binance가 포지션·주문 증거금(격리 포함)을 뺀 값을 주므로 그대로 쓴다.
     */
    private WalletBalanceDTO toWalletBalance(BinanceAccountResponse response) {
        BinanceAccountResponse.Asset usdt = response.getAssets() == null ? null
                : response.getAssets().stream()
                        .filter(a -> QUOTE_ASSET.equals(a.getAsset()))
                        .findFirst()
                        .orElse(null);

        // USDT 자산이 없으면 금액은 null
        return WalletBalanceDTO.builder()
                .totalEquity(usdt != null ? usdt.getMarginBalance() : null)
                .walletBalance(usdt != null ? usdt.getWalletBalance() : null)
                .availableBalance(usdt != null ? usdt.getAvailableBalance() : null)
                .currency(QUOTE_ASSET)
                .build();
    }
}
