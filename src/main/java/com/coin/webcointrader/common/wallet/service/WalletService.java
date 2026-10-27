package com.coin.webcointrader.common.wallet.service;

import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.ExchangeClientRegistry;
import com.coin.webcointrader.common.account.dto.WalletBalanceDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 지갑 잔고 조회 서비스.
 * 거래소별 계정 클라이언트를 통해 사용자의 실전 지갑 잔고를 반환한다.
 */
@Service
@RequiredArgsConstructor
public class WalletService {

    private final ExchangeClientRegistry registry;

    /**
     * 사용자의 실전 지갑 잔고를 조회한다.
     * 거래소 응답 실패(retCode 오류 등)는 예외로 전파한다.
     * 실패를 값으로 숨기면 프론트엔드가 실패를 인지하지 못해
     * 이전에 표시된 값(예: 모의 지갑 잔액)이 화면에 그대로 남는 문제가 있었다.
     *
     * @param userId       사용자 ID
     * @param exchangeType 거래소 타입
     * @return 지갑 잔고 (총 자산, USDT 지갑 잔고, 가용 잔고)
     * @throws CustomException 키 미등록(API_KEY_NOT_FOUND), 조회 실패(GET_WALLET_BALANCE_FAILED),
     *                         미지원 거래소(EXCHANGE_NOT_SUPPORTED)
     */
    public WalletBalanceDTO getWalletBalance(Long userId, ExchangeType exchangeType) {
        return registry.account(exchangeType).getWalletBalance(userId);
    }
}
