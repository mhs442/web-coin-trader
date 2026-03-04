package com.coin.webcointrader.autotrade.repository;

import com.coin.webcointrader.common.entity.Queue;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 패턴 큐 리포지토리.
 */
@Repository
public interface QueueRepository extends JpaRepository<Queue, Long> {
    // 사용자/심볼별 큐 목록 조회 (삭제되지 않은 것만, 정렬 순서 오름차순)
    List<Queue> findByUserIdAndSymbolAndDelYnOrderBySortOrderAsc(Long userId, String symbol, String delYn);

    // 사용자/심볼별 큐 개수 조회 (최대 20개 제한 검증용, 삭제되지 않은 것만)
    long countByUserIdAndSymbolAndDelYn(Long userId, String symbol, String delYn);

    // 활성화된 큐만 조회 (자동매매 실행용)
    List<Queue> findByUserIdAndSymbolAndUseYnAndDelYnOrderBySortOrderAsc(Long userId, String symbol, String useYn, String delYn);

    // 마이페이지: 사용자의 전체 큐 조회 (Sort 동적 정렬)
    List<Queue> findByUserIdAndDelYn(Long userId, String delYn, Sort sort);

    // 마이페이지: 날짜 범위 큐 조회 (Sort 동적 정렬)
    List<Queue> findByUserIdAndDelYnAndCreatedAtBetween(
            Long userId, String delYn, LocalDateTime start, LocalDateTime end, Sort sort);

    // 마이페이지: 전체 큐 페이징 조회
    Page<Queue> findByUserIdAndDelYn(Long userId, String delYn, Pageable pageable);

    // 마이페이지: 날짜 범위 큐 페이징 조회
    Page<Queue> findByUserIdAndDelYnAndCreatedAtBetween(
            Long userId, String delYn, LocalDateTime start, LocalDateTime end, Pageable pageable);
}
