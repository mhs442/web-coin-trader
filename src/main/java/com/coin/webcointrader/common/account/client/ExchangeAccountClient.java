package com.coin.webcointrader.common.account.client;

import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.account.dto.WalletBalanceDTO;

/**
 * 거래소 계정 클라이언트 (API 명세).
 * 지갑 잔고 조회와 API 키 유효성 검증을 제공한다.
 */
public interface ExchangeAccountClient {

    /** 이 클라이언트가 대상으로 하는 거래소 타입을 반환한다. */
    ExchangeType getExchangeType();

    /**
     * 사용자의 지갑 잔고를 조회한다.
     *
     * @param userId API 키를 조회할 사용자 ID
     * @return USDT 기준 잔고
     * @throws com.coin.webcointrader.common.exception.CustomException 조회 실패 시 (GET_WALLET_BALANCE_FAILED)
     */
    WalletBalanceDTO getWalletBalance(Long userId);

    /**
     * API 키가 유효한지 거래소에 확인한다.
     *
     * @param apiKey    평문 API Key
     * @param apiSecret 평문 API Secret
     * @return 유효하면 true
     */
    boolean validateApiKey(String apiKey, String apiSecret);
}
