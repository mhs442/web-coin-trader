package com.coin.webcointrader.autotrade.repository;

import com.coin.webcointrader.common.entity.PatternQueue;
import com.coin.webcointrader.common.enums.TradeMode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 패턴 큐 Repository.
 * 사용자별/심볼별 패턴 큐 조회 및 개수 확인을 제공한다.
 */
public interface PatternQueueRepository extends JpaRepository<PatternQueue, Long> {

    /**
     * 사용자/심볼/거래모드에 해당하는 패턴 큐 목록을 생성일 오름차순으로 조회한다.
     *
     * @param userId    사용자 ID
     * @param symbol    코인 심볼
     * @param tradeMode 거래 모드 (MAIN/SIM)
     * @return 패턴 큐 목록 (createdAt 오름차순)
     */
    List<PatternQueue> findByUserIdAndSymbolAndTradeModeOrderByCreatedAtAsc(Long userId, String symbol, TradeMode tradeMode);

    /**
     * 사용자/심볼/거래모드에 해당하는 패턴 큐 개수를 조회한다.
     *
     * @param userId    사용자 ID
     * @param symbol    코인 심볼
     * @param tradeMode 거래 모드 (MAIN/SIM)
     * @return 패턴 큐 개수
     */
    long countByUserIdAndSymbolAndTradeMode(Long userId, String symbol, TradeMode tradeMode);

    // ─────────────────────────────────────────────
    // 자동매매 세션용 쿼리
    // ─────────────────────────────────────────────

    /**
     * 사용자/심볼/거래모드의 활성화된 패턴 큐 목록을 생성일 오름차순으로 조회한다. (자동매매 세션 동기화용)
     *
     * @param userId    사용자 ID
     * @param symbol    코인 심볼
     * @param isActive  활성화 여부
     * @param tradeMode 거래 모드 (MAIN/SIM)
     * @return 활성 패턴 큐 목록 (createdAt 오름차순)
     */
    List<PatternQueue> findByUserIdAndSymbolAndIsActiveAndTradeModeOrderByCreatedAtAsc(
            Long userId, String symbol, boolean isActive, TradeMode tradeMode);

    // ─────────────────────────────────────────────
    // 마이페이지용 쿼리
    // ─────────────────────────────────────────────

    /**
     * 사용자/거래모드의 패턴 큐를 날짜 범위 + 동적 정렬로 조회한다. (심볼 검색 시 Java 필터용)
     *
     * @param userId    사용자 ID
     * @param tradeMode 거래 모드 (MAIN/SIM)
     * @param start     조회 시작일시
     * @param end       조회 종료일시
     * @param sort      정렬 조건
     * @return 패턴 큐 목록
     */
    List<PatternQueue> findByUserIdAndTradeModeAndCreatedAtBetween(
            Long userId, TradeMode tradeMode, LocalDateTime start, LocalDateTime end, Sort sort);

    /**
     * 사용자/거래모드의 패턴 큐를 날짜 범위 + 페이징 조회한다.
     *
     * @param userId    사용자 ID
     * @param tradeMode 거래 모드 (MAIN/SIM)
     * @param start     조회 시작일시
     * @param end       조회 종료일시
     * @param pageable  페이징 조건
     * @return 패턴 큐 페이지
     */
    Page<PatternQueue> findByUserIdAndTradeModeAndCreatedAtBetween(
            Long userId, TradeMode tradeMode, LocalDateTime start, LocalDateTime end, Pageable pageable);
}
