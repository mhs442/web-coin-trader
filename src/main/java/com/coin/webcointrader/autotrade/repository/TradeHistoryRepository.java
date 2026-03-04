package com.coin.webcointrader.autotrade.repository;

import com.coin.webcointrader.common.entity.TradeHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 거래 히스토리 리포지토리.
 */
@Repository
public interface TradeHistoryRepository extends JpaRepository<TradeHistory, Long> {
    // 사용자/심볼/날짜별 거래 조회 (날짜 내림차순)
    List<TradeHistory> findByUserIdAndSymbolAndCreatedAtBetweenOrderByCreatedAtDesc(
            Long userId, String symbol, LocalDateTime start, LocalDateTime end);

    // 사용자/심볼별 전체 거래 조회 (날짜 내림차순)
    List<TradeHistory> findByUserIdAndSymbolOrderByCreatedAtDesc(Long userId, String symbol);

    // 마이페이지: 전체 거래 조회 (Sort 동적 정렬)
    List<TradeHistory> findByUserId(Long userId, Sort sort);

    // 마이페이지: 날짜 범위 거래 조회 (Sort 동적 정렬)
    List<TradeHistory> findByUserIdAndCreatedAtBetween(
            Long userId, LocalDateTime start, LocalDateTime end, Sort sort);

    // 마이페이지: 전체 거래 페이징 조회
    Page<TradeHistory> findByUserId(Long userId, Pageable pageable);

    // 마이페이지: 날짜 범위 거래 페이징 조회
    Page<TradeHistory> findByUserIdAndCreatedAtBetween(
            Long userId, LocalDateTime start, LocalDateTime end, Pageable pageable);
}
