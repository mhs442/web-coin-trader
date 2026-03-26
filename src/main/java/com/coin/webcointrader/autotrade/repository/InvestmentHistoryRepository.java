package com.coin.webcointrader.autotrade.repository;

import com.coin.webcointrader.common.entity.InvestmentHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 투자 히스토리 리포지토리.
 */
@Repository
public interface InvestmentHistoryRepository extends JpaRepository<InvestmentHistory, Long> {

    /**
     * 사용자의 투자 히스토리를 날짜 범위 + 동적 정렬로 조회한다. (심볼 검색 시 Java 필터용)
     *
     * @param userId 사용자 ID
     * @param start  조회 시작일시
     * @param end    조회 종료일시
     * @param sort   정렬 조건
     * @return 투자 히스토리 목록
     */
    List<InvestmentHistory> findByUserIdAndCreatedAtBetween(
            Long userId, LocalDateTime start, LocalDateTime end, Sort sort);

    /**
     * 사용자의 투자 히스토리를 날짜 범위 + 페이징 조회한다.
     *
     * @param userId   사용자 ID
     * @param start    조회 시작일시
     * @param end      조회 종료일시
     * @param pageable 페이징 조건
     * @return 투자 히스토리 페이지
     */
    Page<InvestmentHistory> findByUserIdAndCreatedAtBetween(
            Long userId, LocalDateTime start, LocalDateTime end, Pageable pageable);

    /**
     * 사용자의 투자 히스토리 손익 합산을 조회한다. (날짜 범위 조건)
     * 결과: [이익금 합산, 손해금 합산]
     *
     * @param userId 사용자 ID
     * @param start  조회 시작일시
     * @param end    조회 종료일시
     * @return List containing single Object[] { totalProfit, totalLoss }
     */
    @Query("SELECT " +
            "COALESCE(SUM(CASE WHEN h.profitLoss > 0 THEN h.profitLoss ELSE 0 END), 0), " +
            "COALESCE(SUM(CASE WHEN h.profitLoss < 0 THEN h.profitLoss ELSE 0 END), 0) " +
            "FROM InvestmentHistory h " +
            "WHERE h.userId = :userId AND h.createdAt BETWEEN :start AND :end")
    List<Object[]> sumProfitLossByUserIdAndCreatedAtBetween(
            @Param("userId") Long userId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    /**
     * 사용자의 투자 히스토리 손익 합산을 조회한다. (날짜 범위 + 심볼 조건)
     * 결과: [이익금 합산, 손해금 합산]
     *
     * @param userId 사용자 ID
     * @param start  조회 시작일시
     * @param end    조회 종료일시
     * @param symbol 코인 심볼 (LIKE 패턴)
     * @return List containing single Object[] { totalProfit, totalLoss }
     */
    @Query("SELECT " +
            "COALESCE(SUM(CASE WHEN h.profitLoss > 0 THEN h.profitLoss ELSE 0 END), 0), " +
            "COALESCE(SUM(CASE WHEN h.profitLoss < 0 THEN h.profitLoss ELSE 0 END), 0) " +
            "FROM InvestmentHistory h " +
            "WHERE h.userId = :userId AND h.createdAt BETWEEN :start AND :end " +
            "AND UPPER(h.symbol) LIKE UPPER(CONCAT('%', :symbol, '%'))")
    List<Object[]> sumProfitLossByUserIdAndCreatedAtBetweenAndSymbol(
            @Param("userId") Long userId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            @Param("symbol") String symbol);
}
