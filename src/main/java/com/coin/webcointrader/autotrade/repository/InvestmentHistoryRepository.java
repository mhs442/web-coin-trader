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

    /**
     * 사용자의 투자 히스토리를 심볼 포함 + 날짜 범위 + 페이징 조회한다. (DB 레벨 심볼 필터)
     *
     * @param userId   사용자 ID
     * @param symbol   코인 심볼 (대소문자 무시 부분 일치)
     * @param start    조회 시작일시
     * @param end      조회 종료일시
     * @param pageable 페이징 조건
     * @return 투자 히스토리 페이지
     */
    Page<InvestmentHistory> findByUserIdAndSymbolContainingIgnoreCaseAndCreatedAtBetween(
            Long userId, String symbol, LocalDateTime start, LocalDateTime end, Pageable pageable);

    /**
     * 차트용 - 일별 손익 집계를 조회한다.
     * 결과: [날짜(String), 손익 합산]
     *
     * @param userId 사용자 ID
     * @param start  조회 시작일시
     * @param end    조회 종료일시
     * @return List of Object[] { date(String), profitLoss }
     */
    @Query("SELECT FUNCTION('DATE_FORMAT', h.createdAt, '%Y-%m-%d'), SUM(h.profitLoss) " +
            "FROM InvestmentHistory h " +
            "WHERE h.userId = :userId AND h.createdAt BETWEEN :start AND :end " +
            "GROUP BY FUNCTION('DATE_FORMAT', h.createdAt, '%Y-%m-%d') " +
            "ORDER BY FUNCTION('DATE_FORMAT', h.createdAt, '%Y-%m-%d') ASC")
    List<Object[]> findDailyProfitLoss(
            @Param("userId") Long userId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    /**
     * 차트용 - 심볼별 손익 집계를 조회한다.
     * 결과: [심볼, 손익 합산]
     *
     * @param userId 사용자 ID
     * @param start  조회 시작일시
     * @param end    조회 종료일시
     * @return List of Object[] { symbol, profitLoss }
     */
    @Query("SELECT h.symbol, SUM(h.profitLoss) " +
            "FROM InvestmentHistory h " +
            "WHERE h.userId = :userId AND h.createdAt BETWEEN :start AND :end " +
            "GROUP BY h.symbol " +
            "ORDER BY SUM(h.profitLoss) DESC")
    List<Object[]> findProfitLossBySymbol(
            @Param("userId") Long userId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    /**
     * 차트용 - 승패 카운트 및 전체 건수를 조회한다.
     * 결과: [익절 건수, 손절 건수, 전체 건수]
     *
     * @param userId 사용자 ID
     * @param start  조회 시작일시
     * @param end    조회 종료일시
     * @return List of Object[] { winCount, lossCount, totalCount }
     */
    @Query("SELECT " +
            "SUM(CASE WHEN h.profitLoss > 0 THEN 1 ELSE 0 END), " +
            "SUM(CASE WHEN h.profitLoss <= 0 THEN 1 ELSE 0 END), " +
            "COUNT(h) " +
            "FROM InvestmentHistory h " +
            "WHERE h.userId = :userId AND h.createdAt BETWEEN :start AND :end")
    List<Object[]> countWinLoss(
            @Param("userId") Long userId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    /**
     * 차트용 - 심볼 필터 일별 손익 집계를 조회한다.
     * 결과: [날짜(String), 손익 합산]
     *
     * @param userId 사용자 ID
     * @param start  조회 시작일시
     * @param end    조회 종료일시
     * @param symbol 코인 심볼 (대소문자 무시 부분 일치)
     * @return List of Object[] { date(String), profitLoss }
     */
    @Query("SELECT FUNCTION('DATE_FORMAT', h.createdAt, '%Y-%m-%d'), SUM(h.profitLoss) " +
            "FROM InvestmentHistory h " +
            "WHERE h.userId = :userId AND h.createdAt BETWEEN :start AND :end " +
            "AND UPPER(h.symbol) LIKE UPPER(CONCAT('%', :symbol, '%')) " +
            "GROUP BY FUNCTION('DATE_FORMAT', h.createdAt, '%Y-%m-%d') " +
            "ORDER BY FUNCTION('DATE_FORMAT', h.createdAt, '%Y-%m-%d') ASC")
    List<Object[]> findDailyProfitLossBySymbol(
            @Param("userId") Long userId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            @Param("symbol") String symbol);

    /**
     * 차트용 - 심볼 필터 심볼별 손익 집계를 조회한다.
     * 결과: [심볼, 손익 합산]
     *
     * @param userId 사용자 ID
     * @param start  조회 시작일시
     * @param end    조회 종료일시
     * @param symbol 코인 심볼 (대소문자 무시 부분 일치)
     * @return List of Object[] { symbol, profitLoss }
     */
    @Query("SELECT h.symbol, SUM(h.profitLoss) " +
            "FROM InvestmentHistory h " +
            "WHERE h.userId = :userId AND h.createdAt BETWEEN :start AND :end " +
            "AND UPPER(h.symbol) LIKE UPPER(CONCAT('%', :symbol, '%')) " +
            "GROUP BY h.symbol " +
            "ORDER BY SUM(h.profitLoss) DESC")
    List<Object[]> findProfitLossBySymbolFiltered(
            @Param("userId") Long userId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            @Param("symbol") String symbol);

    /**
     * 차트용 - 심볼 필터 승패 카운트 및 전체 건수를 조회한다.
     * 결과: [익절 건수, 손절 건수, 전체 건수]
     *
     * @param userId 사용자 ID
     * @param start  조회 시작일시
     * @param end    조회 종료일시
     * @param symbol 코인 심볼 (대소문자 무시 부분 일치)
     * @return List of Object[] { winCount, lossCount, totalCount }
     */
    @Query("SELECT " +
            "SUM(CASE WHEN h.profitLoss > 0 THEN 1 ELSE 0 END), " +
            "SUM(CASE WHEN h.profitLoss <= 0 THEN 1 ELSE 0 END), " +
            "COUNT(h) " +
            "FROM InvestmentHistory h " +
            "WHERE h.userId = :userId AND h.createdAt BETWEEN :start AND :end " +
            "AND UPPER(h.symbol) LIKE UPPER(CONCAT('%', :symbol, '%'))")
    List<Object[]> countWinLossBySymbol(
            @Param("userId") Long userId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            @Param("symbol") String symbol);

    /**
     * 선택한 ID 목록 중 해당 사용자 소유 투자 히스토리를 삭제한다. (선택 삭제)
     *
     * @param ids    삭제할 투자 히스토리 ID 목록
     * @param userId 사용자 ID (권한 검증)
     */
    void deleteAllByIdInAndUserId(List<Long> ids, Long userId);

    /**
     * 해당 사용자의 전체 투자 히스토리를 삭제한다. (전체 삭제)
     *
     * @param userId 사용자 ID
     */
    void deleteAllByUserId(Long userId);

    /**
     * 투자 ID와 사용자 ID로 투자 히스토리 단건 조회 (권한 검증 포함).
     *
     * @param id     투자 히스토리 ID
     * @param userId 사용자 ID
     * @return 투자 히스토리 (없으면 empty)
     */
    java.util.Optional<InvestmentHistory> findByIdAndUserId(Long id, Long userId);
}
