package com.coin.webcointrader.sim.repository;

import com.coin.webcointrader.common.entity.SimTradeHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
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
     * @param queueStepId 패턴 단계 ID
     * @param userId      사용자 ID (권한 검증)
     * @return 모의 거래 내역 목록 (체결 일시 오름차순)
     */
    List<SimTradeHistory> findByQueueStepIdAndUserIdOrderByCreatedAtAsc(Long queueStepId, Long userId);

    /**
     * 특정 투자 사이클에 속한 모의 거래만 조회한다.
     *
     * @param queueStepId 패턴 단계 ID
     * @param userId      사용자 ID (권한 검증)
     * @param upperBound  투자 히스토리 생성 일시 (이 시각 이하만 조회)
     * @return 최신순 모의 거래 목록 (서비스에서 해당 사이클 쌍 추출)
     */
    List<SimTradeHistory> findByQueueStepIdAndUserIdAndCreatedAtLessThanEqualOrderByCreatedAtDesc(
            Long queueStepId, Long userId, LocalDateTime upperBound);

    /**
     * 선택한 ID 목록 중 해당 사용자 소유 모의 거래 히스토리를 삭제한다. (선택 삭제)
     *
     * @param ids    삭제할 모의 거래 히스토리 ID 목록
     * @param userId 사용자 ID (권한 검증)
     */
    void deleteAllByIdInAndUserId(List<Long> ids, Long userId);

    /**
     * 해당 사용자의 전체 모의 거래 히스토리를 삭제한다. (전체 삭제)
     *
     * @param userId 사용자 ID
     */
    void deleteAllByUserId(Long userId);
}
