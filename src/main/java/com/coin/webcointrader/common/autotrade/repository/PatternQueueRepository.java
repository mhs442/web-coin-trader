package com.coin.webcointrader.common.autotrade.repository;

import com.coin.webcointrader.common.autotrade.entity.PatternQueue;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.enums.TradeMode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 패턴 큐 Repository.
 * 사용자별/심볼별 패턴 큐 조회 및 개수 확인을 제공한다.
 */
public interface PatternQueueRepository extends JpaRepository<PatternQueue, Long> {

    /**
     * 사용자/심볼/거래모드/거래소에 해당하는 패턴 큐 목록을 생성일 오름차순으로 조회한다.
     *
     * @param userId       사용자 ID
     * @param symbol       코인 심볼
     * @param tradeMode    거래 모드 (MAIN/SIM)
     * @param exchangeType 거래소 타입
     * @return 패턴 큐 목록 (createdAt 오름차순)
     */
    List<PatternQueue> findByUserIdAndSymbolAndTradeModeAndExchangeTypeOrderByCreatedAtAsc(
            Long userId, String symbol, TradeMode tradeMode, ExchangeType exchangeType);

    /**
     * 사용자/거래모드/거래소에 해당하는 전체 패턴 큐 목록을 생성일 오름차순으로 조회한다. (심볼 무관 - 큐 가져오기용)
     *
     * @param userId       사용자 ID
     * @param tradeMode    거래 모드 (MAIN/SIM)
     * @param exchangeType 거래소 타입
     * @return 전체 패턴 큐 목록 (createdAt 오름차순)
     */
    List<PatternQueue> findByUserIdAndTradeModeAndExchangeTypeOrderByCreatedAtAsc(
            Long userId, TradeMode tradeMode, ExchangeType exchangeType);

    /**
     * 사용자/거래모드에 해당하는 모든 거래소의 패턴 큐 목록을 생성일 오름차순으로 조회한다. (심볼·거래소 무관 - 큐 가져오기용)
     *
     * @param userId    사용자 ID
     * @param tradeMode 거래 모드 (MAIN/SIM)
     * @return 전체 거래소 패턴 큐 목록 (createdAt 오름차순)
     */
    List<PatternQueue> findByUserIdAndTradeModeOrderByCreatedAtAsc(Long userId, TradeMode tradeMode);

    /**
     * 사용자/심볼/거래모드/거래소에 해당하는 패턴 큐 개수를 조회한다.
     *
     * @param userId       사용자 ID
     * @param symbol       코인 심볼
     * @param tradeMode    거래 모드 (MAIN/SIM)
     * @param exchangeType 거래소 타입
     * @return 패턴 큐 개수
     */
    long countByUserIdAndSymbolAndTradeModeAndExchangeType(
            Long userId, String symbol, TradeMode tradeMode, ExchangeType exchangeType);

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

    /**
     * 사용자/심볼/거래모드/거래소의 활성화된 패턴 큐 목록을 생성일 오름차순으로 조회한다.
     *
     * @param userId       사용자 ID
     * @param symbol       코인 심볼
     * @param isActive     활성화 여부
     * @param tradeMode    거래 모드 (MAIN/SIM)
     * @param exchangeType 거래소 타입
     * @return 활성 패턴 큐 목록 (createdAt 오름차순)
     */
    List<PatternQueue> findByUserIdAndSymbolAndIsActiveAndTradeModeAndExchangeTypeOrderByCreatedAtAsc(
            Long userId, String symbol, boolean isActive, TradeMode tradeMode, ExchangeType exchangeType);

    // ─────────────────────────────────────────────
    // 마이페이지용 쿼리
    // ─────────────────────────────────────────────

    /**
     * 사용자/거래모드/거래소의 패턴 큐를 날짜 범위 + 페이징 조회한다.
     *
     * @param userId       사용자 ID
     * @param tradeMode    거래 모드 (MAIN/SIM)
     * @param exchangeType 거래소 타입
     * @param start     조회 시작일시
     * @param end       조회 종료일시
     * @param pageable  페이징 조건
     * @return 패턴 큐 페이지
     */
    Page<PatternQueue> findByUserIdAndTradeModeAndExchangeTypeAndCreatedAtBetween(
            Long userId, TradeMode tradeMode, ExchangeType exchangeType,
            LocalDateTime start, LocalDateTime end, Pageable pageable);

    /**
     * 사용자/거래모드/거래소의 패턴 큐를 심볼 키워드(대소문자 무시, 부분일치) + 날짜 범위 + 페이징으로 DB 레벨에서 조회한다.
     *
     * @param userId       사용자 ID
     * @param symbol       심볼 검색 키워드
     * @param tradeMode    거래 모드 (MAIN/SIM)
     * @param exchangeType 거래소 타입
     * @param start     조회 시작일시
     * @param end       조회 종료일시
     * @param pageable  페이징 조건
     * @return 패턴 큐 페이지
     */
    Page<PatternQueue> findByUserIdAndSymbolContainingIgnoreCaseAndTradeModeAndExchangeTypeAndCreatedAtBetween(
            Long userId, String symbol, TradeMode tradeMode, ExchangeType exchangeType,
            LocalDateTime start, LocalDateTime end, Pageable pageable);

    /**
     * 거래량 급변 중지 예약 사유만 변경한다. (엔진이 들고 있는 오래된 엔티티로 다른 컬럼을 덮어쓰지 않도록 단일 컬럼 UPDATE)
     *
     * @param id     큐 ID
     * @param reason 예약 사유 (null: 예약 해제)
     * @return 변경된 행 수
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE PatternQueue q SET q.volumeStopReason = :reason WHERE q.id = :id")
    int updateVolumeStopReason(@Param("id") Long id, @Param("reason") String reason);
}
