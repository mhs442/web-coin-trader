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

    /**
     * 사용자의 거래 히스토리를 날짜 범위 + 동적 정렬로 조회한다. (심볼 검색 시 Java 필터용)
     *
     * @param userId 사용자 ID
     * @param start  조회 시작일시
     * @param end    조회 종료일시
     * @param sort   정렬 조건
     * @return 거래 히스토리 목록
     */
    List<TradeHistory> findByUserIdAndCreatedAtBetween(
            Long userId, LocalDateTime start, LocalDateTime end, Sort sort);

    /**
     * 사용자의 거래 히스토리를 날짜 범위 + 페이징 조회한다.
     *
     * @param userId   사용자 ID
     * @param start    조회 시작일시
     * @param end      조회 종료일시
     * @param pageable 페이징 조건
     * @return 거래 히스토리 페이지
     */
    Page<TradeHistory> findByUserIdAndCreatedAtBetween(
            Long userId, LocalDateTime start, LocalDateTime end, Pageable pageable);
}
