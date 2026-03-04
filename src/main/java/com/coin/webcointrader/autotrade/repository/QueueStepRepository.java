package com.coin.webcointrader.autotrade.repository;

import com.coin.webcointrader.common.entity.QueueStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 패턴별 단계 리포지토리.
 */
@Repository
public interface QueueStepRepository extends JpaRepository<QueueStep, Long> {
}
