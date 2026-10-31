package com.coin.webcointrader.common.sim.repository;

import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.sim.entity.SimTradeHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 모의투자 거래 히스토리 리포지토리.
 */
@Repository
public interface SimTradeHistoryRepository extends JpaRepository<SimTradeHistory, Long> {

    /**
     * 사용자의 모의 거래 히스토리를 날짜 범위 + 동적 정렬로 조회한다.
     *
     * @param userId 사용자 ID
     * @param start  조회 시작일시
     * @param end    조회 종료일시
     * @param sort   정렬 조건
     * @return 모의 거래 히스토리 목록
     */
    List<SimTradeHistory> findByUserIdAndCreatedAtBetween(
            Long userId, LocalDateTime start, LocalDateTime end, Sort sort);

    /**
     * 사용자의 모의 거래 히스토리를 날짜 범위 + 페이징 조회한다.
     *
     * @param userId   사용자 ID
     * @param start    조회 시작일시
     * @param end      조회 종료일시
     * @param pageable 페이징 조건
     * @return 모의 거래 히스토리 페이지
     */
    Page<SimTradeHistory> findByUserIdAndCreatedAtBetween(
            Long userId, LocalDateTime start, LocalDateTime end, Pageable pageable);

    /**
     * 투자 행 클릭 시 해당 투자 사이클의 모의 거래 내역을 조회한다.
     *
     * @param patternId   패턴 ID
     * @param userId      사용자 ID (권한 검증)
     * @return 모의 거래 내역 목록 (체결 일시 오름차순)
     */
    List<SimTradeHistory> findByPatternIdAndUserIdOrderByCreatedAtAsc(Long patternId, Long userId);

    /**
     * 특정 투자 사이클에 속한 모의 거래만 조회한다.
     *
     * @param patternId   패턴 ID
     * @param userId      사용자 ID (권한 검증)
     * @param upperBound  투자 히스토리 생성 일시 (이 시각 이하만 조회)
     * @return 최신순 모의 거래 목록 (서비스에서 해당 사이클 쌍 추출)
     */
    List<SimTradeHistory> findByPatternIdAndUserIdAndCreatedAtLessThanEqualOrderByCreatedAtDesc(
            Long patternId, Long userId, LocalDateTime upperBound);

    // 실패·중지 기록 조건: 주문 실패(FAILED) 또는 큐 중지(stop) 기록 — 중지 기록은 SUCCESS로 저장되므로 OR로 묶는다
    String FAILED_OR_STOPPED = "(h.orderResult = com.coin.webcointrader.common.enums.OrderResult.FAILED OR LOWER(h.orderType) = 'stop')";

    /**
     * 마이페이지 실패·중지 기록을 페이징 조회한다.
     *
     * @param userId       사용자 ID
     * @param exchangeType 거래소 타입
     * @param symbol       심볼 키워드 (대소문자 무시 부분일치, null이면 전체)
     * @param start        조회 시작일시
     * @param end          조회 종료일시
     * @param pageable     페이징·정렬 조건
     * @return 실패·중지 모의 거래 히스토리 페이지
     */
    @Query("SELECT h FROM SimTradeHistory h " +
            "WHERE h.userId = :userId AND h.exchangeType = :exchangeType AND h.createdAt BETWEEN :start AND :end " +
            "AND " + FAILED_OR_STOPPED + " " +
            "AND (:symbol IS NULL OR UPPER(h.symbol) LIKE UPPER(CONCAT('%', :symbol, '%')))")
    Page<SimTradeHistory> findFailedOrStopped(
            @Param("userId") Long userId,
            @Param("exchangeType") ExchangeType exchangeType,
            @Param("symbol") String symbol,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            Pageable pageable);

    /**
     * 선택한 ID 목록 중 해당 사용자 소유의 실패·중지 기록만 삭제한다. (마이페이지 선택 삭제)
     * 성공 거래는 투자 상세 조회에 쓰이므로 ID가 섞여 들어와도 지우지 않는다.
     *
     * @param ids    삭제할 모의 거래 히스토리 ID 목록
     * @param userId 사용자 ID (권한 검증)
     */
    @Modifying
    @Query("DELETE FROM SimTradeHistory h WHERE h.id IN :ids AND h.userId = :userId AND " + FAILED_OR_STOPPED)
    void deleteFailedOrStoppedByIdInAndUserId(@Param("ids") List<Long> ids, @Param("userId") Long userId);

    /**
     * 해당 사용자의 선택 거래소 전체 모의 거래 히스토리를 삭제한다. (마이페이지 전체 삭제 — 다른 거래소 데이터는 유지)
     *
     * @param userId 사용자 ID
     * @param exchangeType 거래소 타입
     */
    void deleteAllByUserIdAndExchangeType(Long userId, ExchangeType exchangeType);
}
