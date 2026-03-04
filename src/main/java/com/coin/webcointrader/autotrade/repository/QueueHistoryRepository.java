package com.coin.webcointrader.autotrade.repository;

import com.coin.webcointrader.common.entity.QueueHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 큐 변경 히스토리 리포지토리.
 */
@Repository
public interface QueueHistoryRepository extends JpaRepository<QueueHistory, Long> {
    // 특정 큐의 변경 이력 조회 (시간순)
    List<QueueHistory> findByQueueIdOrderByCreatedAtDesc(Long queueId);
}
