package com.coin.webcointrader.common.trade.service;

import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.trade.dto.DailyProfitLossDTO;
import com.coin.webcointrader.common.trade.dto.InvestmentHistoryDTO;
import com.coin.webcointrader.common.trade.dto.ProfitLossSummaryDTO;
import com.coin.webcointrader.common.trade.dto.SymbolProfitLossDTO;
import com.coin.webcointrader.common.trade.dto.TradeHistoryDTO;
import com.coin.webcointrader.common.trade.dto.WinLossCountDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 거래/투자 히스토리 저장·조회 계약.
 * MAIN(실전)과 SIM(모의투자)이 같은 형태로 구현하며, 다른 도메인은 엔티티·Repository 대신 이 계약(DTO)만 사용한다.
 *
 * @see TradeHistoryService MAIN 구현
 * @see com.coin.webcointrader.common.sim.service.SimHistoryService SIM 구현
 */
public interface HistoryService {

    // 거래 히스토리 실패 사유 최대 길이 (DB error_message VARCHAR(500))
    int ERROR_MESSAGE_MAX_LENGTH = 500;

    /**
     * 실패 사유를 DB 컬럼 길이에 맞게 자른다.
     * 거래소·DB 예외 메시지가 그대로 들어오는 경우가 있어, 길이 초과로 이력 저장 자체가 실패하지 않도록 한다.
     *
     * @param message 실패 사유 (null 가능)
     * @return 최대 {@link #ERROR_MESSAGE_MAX_LENGTH}자로 자른 실패 사유 (null이면 null)
     */
    static String truncateErrorMessage(String message) {
        if (message == null || message.length() <= ERROR_MESSAGE_MAX_LENGTH) {
            return message;
        }
        return message.substring(0, ERROR_MESSAGE_MAX_LENGTH);
    }

    /**
     * 거래 히스토리를 저장한다.
     *
     * @param dto 저장할 거래 히스토리
     */
    void saveTrade(TradeHistoryDTO dto);

    /**
     * 투자 히스토리를 저장한다.
     *
     * @param dto 저장할 투자 히스토리
     */
    void saveInvestment(InvestmentHistoryDTO dto);

    /**
     * 투자 히스토리를 기간으로 페이징 조회한다.
     *
     * @param userId   사용자 ID
     * @param exchangeType 거래소 타입
     * @param symbol   심볼 키워드 (null/공백이면 필터 없음, 있으면 부분 일치·대소문자 무시)
     * @param start    조회 시작 일시
     * @param end      조회 종료 일시
     * @param pageable 페이징·정렬 조건
     * @return 투자 히스토리 페이지
     */
    Page<InvestmentHistoryDTO> findInvestments(Long userId, ExchangeType exchangeType, String symbol,
                                               LocalDateTime start, LocalDateTime end, Pageable pageable);

    /**
     * 기간 내 수익/손실 합계를 조회한다.
     *
     * @param userId 사용자 ID
     * @param exchangeType 거래소 타입
     * @param symbol 심볼 키워드 (null/공백이면 필터 없음)
     * @param start  조회 시작 일시
     * @param end    조회 종료 일시
     * @return 수익 합 / 손실 합
     */
    ProfitLossSummaryDTO sumProfitLoss(Long userId, ExchangeType exchangeType, String symbol, LocalDateTime start, LocalDateTime end);

    /**
     * 기간 내 승패 카운트를 조회한다. (심볼 필터 없음)
     *
     * @param userId 사용자 ID
     * @param exchangeType 거래소 타입
     * @param start  조회 시작 일시
     * @param end    조회 종료 일시
     * @return 승/패/전체 건수
     */
    WinLossCountDTO countWinLoss(Long userId, ExchangeType exchangeType, LocalDateTime start, LocalDateTime end);

    /**
     * 기간 내 특정 심볼 승패 카운트를 조회한다.
     *
     * @param userId 사용자 ID
     * @param exchangeType 거래소 타입
     * @param symbol 심볼 키워드 (부분 일치·대소문자 무시)
     * @param start  조회 시작 일시
     * @param end    조회 종료 일시
     * @return 승/패/전체 건수
     */
    WinLossCountDTO countWinLossBySymbol(Long userId, ExchangeType exchangeType, String symbol, LocalDateTime start, LocalDateTime end);

    /**
     * 일별 손익 합계를 날짜 오름차순으로 조회한다.
     *
     * @param userId 사용자 ID
     * @param exchangeType 거래소 타입
     * @param symbol 심볼 키워드 (null/공백이면 필터 없음)
     * @param start  조회 시작 일시
     * @param end    조회 종료 일시
     * @return 일별 손익 목록
     */
    List<DailyProfitLossDTO> findDailyProfitLoss(Long userId, ExchangeType exchangeType, String symbol, LocalDateTime start, LocalDateTime end);

    /**
     * 심볼별 손익 합계를 손익 내림차순으로 조회한다.
     *
     * @param userId 사용자 ID
     * @param exchangeType 거래소 타입
     * @param symbol 심볼 키워드 (null/공백이면 필터 없음)
     * @param start  조회 시작 일시
     * @param end    조회 종료 일시
     * @return 심볼별 손익 목록
     */
    List<SymbolProfitLossDTO> findProfitLossBySymbol(Long userId, ExchangeType exchangeType, String symbol, LocalDateTime start, LocalDateTime end);

    /**
     * 투자 히스토리 1건을 조회한다. (타 사용자 데이터 접근 방지를 위해 userId 함께 검증)
     *
     * @param id     투자 히스토리 ID
     * @param userId 사용자 ID
     * @return 투자 히스토리, 없으면 empty
     */
    Optional<InvestmentHistoryDTO> findInvestment(Long id, Long userId);

    /**
     * 특정 단계의 거래를 상한 일시 이하로 최신순 조회한다.
     *
     * @param patternId   패턴 ID
     * @param userId      사용자 ID
     * @param upperBound  생성 일시 상한 (포함)
     * @return 거래 목록 (createdAt 내림차순)
     */
    List<TradeHistoryDTO> findTradesUpTo(Long patternId, Long userId, LocalDateTime upperBound);

    /**
     * 마이페이지 실패·중지 기록(주문 실패 + 큐 중지)을 페이징 조회한다.
     *
     * @param userId       사용자 ID
     * @param exchangeType 거래소 타입
     * @param symbol       심볼 키워드 (부분일치, 비어 있으면 전체)
     * @param start        조회 시작일시
     * @param end          조회 종료일시
     * @param pageable     페이징·정렬 조건
     * @return 실패·중지 거래 히스토리 페이지 (사유는 errorMessage)
     */
    Page<TradeHistoryDTO> findFailedOrStoppedTrades(Long userId, ExchangeType exchangeType, String symbol,
                                                    LocalDateTime start, LocalDateTime end, Pageable pageable);

    /** 선택한 거래 히스토리 중 실패·중지 기록만 삭제한다. (본인 데이터만, 성공 거래는 유지) */
    void deleteTrades(List<Long> ids, Long userId);

    /** 사용자의 선택 거래소 전체 거래 히스토리를 삭제한다. (다른 거래소 데이터는 유지) */
    void deleteAllTrades(Long userId, ExchangeType exchangeType);

    /** 선택한 투자 히스토리를 삭제한다. (본인 데이터만) */
    void deleteInvestments(List<Long> ids, Long userId);

    /** 사용자의 선택 거래소 전체 투자 히스토리를 삭제한다. (다른 거래소 데이터는 유지) */
    void deleteAllInvestments(Long userId, ExchangeType exchangeType);
}
