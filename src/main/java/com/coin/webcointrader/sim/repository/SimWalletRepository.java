package com.coin.webcointrader.sim.repository;

import com.coin.webcointrader.common.entity.SimWallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 모의투자 가상 지갑 리포지토리.
 */
@Repository
public interface SimWalletRepository extends JpaRepository<SimWallet, Long> {

    /**
     * 사용자 ID로 가상 지갑을 조회한다.
     *
     * @param userId 사용자 ID
     * @return 가상 지갑 (없으면 empty)
     */
    Optional<SimWallet> findByUserId(Long userId);
}
