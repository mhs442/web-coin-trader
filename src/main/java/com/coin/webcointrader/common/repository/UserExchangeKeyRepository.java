package com.coin.webcointrader.common.repository;

import com.coin.webcointrader.common.entity.UserExchangeKey;
import com.coin.webcointrader.common.enums.ExchangeType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 사용자별 거래소 API Key Repository.
 */
public interface UserExchangeKeyRepository extends JpaRepository<UserExchangeKey, Long> {

    /**
     * 사용자 ID와 거래소 타입으로 API Key를 조회한다.
     *
     * @param userId       사용자 ID
     * @param exchangeType 거래소 타입
     * @return API Key 엔티티 (없으면 empty)
     */
    Optional<UserExchangeKey> findByUserIdAndExchangeType(Long userId, ExchangeType exchangeType);
}
