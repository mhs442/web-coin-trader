package com.coin.webcointrader.common.sim.service;

import com.coin.webcointrader.common.account.dto.WalletBalanceDTO;
import com.coin.webcointrader.common.login.entity.User;
import com.coin.webcointrader.common.sim.entity.SimWallet;
import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.sim.repository.SimWalletRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 모의투자 가상 지갑 서비스.
 * SimWallet의 잔고 조회 및 초기화를 담당한다.
 * 잔고 응답은 기존 GetWalletBalanceResponse 형태로 변환하여 프론트와 호환성을 유지한다.
 */
@Service
@RequiredArgsConstructor
public class SimWalletService {

    private final SimWalletRepository simWalletRepository;
    private final EntityManager entityManager;

    /**
     * 사용자의 가상 지갑 잔고를 조회한다.
     * 가상 지갑은 미실현 손익·주문 증거금을 따로 두지 않으므로 세 값 모두 잔고와 동일하다.
     *
     * @param userId 사용자 ID
     * @return 가상 지갑 잔고 (총 자산 = 지갑 잔고 = 가용 잔고)
     * @throws CustomException 가상 지갑이 없는 경우 (USER_NOT_FOUND)
     */
    public WalletBalanceDTO getWalletBalance(Long userId) {
        SimWallet wallet = simWalletRepository.findByUserId(userId)
                .orElseThrow(() -> new CustomException(ExceptionMessage.USER_NOT_FOUND));

        String balanceStr = wallet.getBalance().stripTrailingZeros().toPlainString();
        return WalletBalanceDTO.builder()
                .totalEquity(balanceStr)
                .walletBalance(balanceStr)
                .availableBalance(balanceStr)
                .currency("USDT") // 가상 지갑은 USDT 단위
                .build();
    }

    /**
     * 신규 사용자의 가상 지갑을 기본 잔고(10,000 USDT)로 생성한다. (회원가입 시 호출)
     * User 조회 쿼리 없이 참조(프록시)만 얻어 연관관계를 설정한다.
     *
     * @param userId 지갑 소유자 사용자 ID (이미 저장된 사용자)
     */
    @Transactional
    public void createWallet(Long userId) {
        SimWallet wallet = new SimWallet();
        wallet.setUser(entityManager.getReference(User.class, userId));
        simWalletRepository.save(wallet);
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
