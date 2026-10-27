package com.coin.webcointrader.common.login.repository;

import com.coin.webcointrader.common.login.entity.UserExchangeKey;
import com.coin.webcointrader.common.enums.ExchangeType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
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

    /**
     * 사용자의 거래소 API Key 전체를 조회한다.
     *
     * @param userId 사용자 ID
     * @return 거래소별 API Key 엔티티 목록 (없으면 빈 목록)
     */
    List<UserExchangeKey> findByUserId(Long userId);
}
