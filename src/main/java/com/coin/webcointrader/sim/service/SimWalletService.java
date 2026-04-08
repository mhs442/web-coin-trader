package com.coin.webcointrader.sim.service;

import com.coin.webcointrader.common.dto.response.GetWalletBalanceResponse;
import com.coin.webcointrader.common.entity.SimWallet;
import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.sim.repository.SimWalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 모의투자 가상 지갑 서비스.
 * SimWallet의 잔고 조회 및 초기화를 담당한다.
 * 잔고 응답은 기존 GetWalletBalanceResponse 형태로 변환하여 프론트와 호환성을 유지한다.
 */
@Service
@RequiredArgsConstructor
public class SimWalletService {

    private final SimWalletRepository simWalletRepository;

    /**
     * 사용자의 가상 지갑 잔고를 GetWalletBalanceResponse 형태로 조회한다.
     * 기존 Bybit API 응답 형태와 동일하게 변환하여 프론트엔드 호환성을 유지한다.
     *
     * @param userId 사용자 ID
     * @return 가상 지갑 잔고 응답 (Bybit 형태)
     */
    public GetWalletBalanceResponse getWalletBalance(Long userId) {
        SimWallet wallet = simWalletRepository.findByUserId(userId)
                .orElseThrow(() -> new CustomException(ExceptionMessage.USER_NOT_FOUND));

        String balanceStr = wallet.getBalance().stripTrailingZeros().toPlainString();

        // Bybit 응답 형태로 변환 (프론트엔드 호환)
        GetWalletBalanceResponse.Result.AccountInfo.CoinInfo coinInfo =
                new GetWalletBalanceResponse.Result.AccountInfo.CoinInfo();
        coinInfo.setCoin("USDT");
        coinInfo.setWalletBalance(balanceStr);
        coinInfo.setEquity(balanceStr);
        coinInfo.setAvailableToWithdraw(balanceStr);

        GetWalletBalanceResponse.Result.AccountInfo accountInfo =
                new GetWalletBalanceResponse.Result.AccountInfo();
        accountInfo.setAccountType("SIM");
        accountInfo.setTotalEquity(balanceStr);
        accountInfo.setTotalWalletBalance(balanceStr);
        accountInfo.setCoin(List.of(coinInfo));

        GetWalletBalanceResponse.Result result = new GetWalletBalanceResponse.Result();
        result.setList(List.of(accountInfo));

        GetWalletBalanceResponse response = new GetWalletBalanceResponse();
        response.setRetCode("0");
        response.setRetMsg("OK");
        response.setResult(result);

        return response;
    }

    /**
     * 가상 지갑 잔고에 금액을 추가한다.
     *
     * @param userId 사용자 ID
     * @param amount 추가할 금액 (USDT)
     */
    @Transactional
    public void addBalance(Long userId, BigDecimal amount) {
        SimWallet wallet = simWalletRepository.findByUserId(userId)
                .orElseThrow(() -> new CustomException(ExceptionMessage.USER_NOT_FOUND));

        // 기존 잔고에 금액 추가
        wallet.setBalance(wallet.getBalance().add(amount));
        simWalletRepository.save(wallet);
    }
}
