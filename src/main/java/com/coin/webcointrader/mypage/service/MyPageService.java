package com.coin.webcointrader.mypage.service;

import com.coin.webcointrader.autotrade.repository.QueueRepository;
import com.coin.webcointrader.autotrade.repository.TradeHistoryRepository;
import com.coin.webcointrader.common.dto.response.PageResponse;
import com.coin.webcointrader.common.entity.Queue;
import com.coin.webcointrader.common.entity.TradeHistory;
import com.coin.webcointrader.mypage.dto.MyPagePatternResponse;
import com.coin.webcointrader.mypage.dto.TradeHistoryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 마이페이지 서비스.
 * 사용자의 패턴(큐) 목록과 거래 히스토리를 날짜 범위·심볼 키워드 조건으로 조회한다.
 */
@Service
@RequiredArgsConstructor
public class MyPageService {
    private final QueueRepository queueRepository;
    private final TradeHistoryRepository tradeHistoryRepository;

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 사용자의 패턴(큐) 목록을 페이징하여 조회한다.
     * 심볼 키워드가 없으면 DB 페이징, 있으면 전체 조회 후 Java 필터 + 수동 페이징.
     *
     * @param userId    사용자 ID
     * @param symbol    심볼 검색 키워드 (null 또는 빈 문자열이면 전체 조회)
     * @param startDate 조회 시작일 (yyyy-MM-dd, null이면 날짜 필터 미적용)
     * @param endDate   조회 종료일 (yyyy-MM-dd, null이면 날짜 필터 미적용)
     * @param sort      정렬 방향 ("asc" 또는 그 외, 기본 내림차순)
     * @param page      페이지 번호 (0-based)
     * @param size      페이지 크기
     * @return 페이징된 패턴 응답
     */
    public PageResponse<MyPagePatternResponse> getPatterns(Long userId, String symbol,
                                                            String startDate, String endDate,
                                                            String sort, int page, int size) {
        Sort dbSort = buildSort("createdAt", sort);
        boolean hasDateRange = hasValue(startDate) && hasValue(endDate);

        // 심볼 키워드가 있으면 전체 조회 후 Java 필터 + 수동 페이징
        if (hasValue(symbol)) {
            List<Queue> queues;
            if (hasDateRange) {
                LocalDateTime start = LocalDate.parse(startDate).atStartOfDay();
                LocalDateTime end = LocalDate.parse(endDate).atTime(LocalTime.MAX);
                queues = queueRepository.findByUserIdAndDelYnAndCreatedAtBetween(userId, "N", start, end, dbSort);
            } else {
                queues = queueRepository.findByUserIdAndDelYn(userId, "N", dbSort);
            }

            // 코인 텍스트 포함 검색 (LIKE '%keyword%' — 인덱스 불가, Java 필터)
            String keyword = symbol.toUpperCase();
            List<MyPagePatternResponse> filtered = queues.stream()
                    .filter(q -> q.getSymbol().toUpperCase().contains(keyword))
                    .map(this::toPatternResponse)
                    .toList();

            return PageResponse.fromList(filtered, page, size);
        }

        // 심볼 키워드 없으면 DB 페이징 사용
        Pageable pageable = PageRequest.of(page, size, dbSort);
        Page<Queue> queuePage;
        if (hasDateRange) {
            LocalDateTime start = LocalDate.parse(startDate).atStartOfDay();
            LocalDateTime end = LocalDate.parse(endDate).atTime(LocalTime.MAX);
            queuePage = queueRepository.findByUserIdAndDelYnAndCreatedAtBetween(userId, "N", start, end, pageable);
        } else {
            queuePage = queueRepository.findByUserIdAndDelYn(userId, "N", pageable);
        }

        return PageResponse.from(queuePage, this::toPatternResponse);
    }

    /**
     * 사용자의 거래 히스토리를 페이징하여 조회한다.
     * 심볼 키워드가 없으면 DB 페이징, 있으면 전체 조회 후 Java 필터 + 수동 페이징.
     *
     * @param userId    사용자 ID
     * @param symbol    심볼 검색 키워드 (null 또는 빈 문자열이면 전체 조회)
     * @param startDate 조회 시작일 (yyyy-MM-dd, null이면 날짜 필터 미적용)
     * @param endDate   조회 종료일 (yyyy-MM-dd, null이면 날짜 필터 미적용)
     * @param sort      정렬 방향 ("asc" 또는 그 외, 기본 내림차순)
     * @param page      페이지 번호 (0-based)
     * @param size      페이지 크기
     * @return 페이징된 거래 히스토리 응답
     */
    public PageResponse<TradeHistoryResponse> getTradeHistories(Long userId, String symbol,
                                                                  String startDate, String endDate,
                                                                  String sort, int page, int size) {
        Sort dbSort = buildSort("createdAt", sort);
        boolean hasDateRange = hasValue(startDate) && hasValue(endDate);

        // 심볼 키워드가 있으면 전체 조회 후 Java 필터 + 수동 페이징
        if (hasValue(symbol)) {
            List<TradeHistory> histories;
            if (hasDateRange) {
                LocalDateTime start = LocalDate.parse(startDate).atStartOfDay();
                LocalDateTime end = LocalDate.parse(endDate).atTime(LocalTime.MAX);
                histories = tradeHistoryRepository.findByUserIdAndCreatedAtBetween(userId, start, end, dbSort);
            } else {
                histories = tradeHistoryRepository.findByUserId(userId, dbSort);
            }

            // 코인 텍스트 포함 검색 (LIKE '%keyword%' — 인덱스 불가, Java 필터)
            String keyword = symbol.toUpperCase();
            List<TradeHistoryResponse> filtered = histories.stream()
                    .filter(h -> h.getSymbol().toUpperCase().contains(keyword))
                    .map(this::toTradeResponse)
                    .toList();

            return PageResponse.fromList(filtered, page, size);
        }

        // 심볼 키워드 없으면 DB 페이징 사용
        Pageable pageable = PageRequest.of(page, size, dbSort);
        Page<TradeHistory> historyPage;
        if (hasDateRange) {
            LocalDateTime start = LocalDate.parse(startDate).atStartOfDay();
            LocalDateTime end = LocalDate.parse(endDate).atTime(LocalTime.MAX);
            historyPage = tradeHistoryRepository.findByUserIdAndCreatedAtBetween(userId, start, end, pageable);
        } else {
            historyPage = tradeHistoryRepository.findByUserId(userId, pageable);
        }

        return PageResponse.from(historyPage, this::toTradeResponse);
    }

    /**
     * 정렬 조건 객체를 생성한다.
     *
     * @param property  정렬 기준 필드명
     * @param direction 정렬 방향 문자열 ("asc" 또는 그 외)
     * @return Sort 객체
     */
    private Sort buildSort(String property, String direction) {
        return "asc".equalsIgnoreCase(direction)
                ? Sort.by(Sort.Direction.ASC, property)
                : Sort.by(Sort.Direction.DESC, property);
    }

    /**
     * 문자열이 null이 아니고 공백이 아닌지 확인한다.
     *
     * @param value 검사할 문자열
     * @return 값이 존재하면 true
     */
    private boolean hasValue(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Queue 엔티티를 MyPagePatternResponse DTO로 변환한다.
     *
     * @param queue Queue 엔티티
     * @return 패턴 응답 DTO
     */
    private MyPagePatternResponse toPatternResponse(Queue queue) {
        List<MyPagePatternResponse.StepResponse> steps = queue.getSteps().stream()
                .map(s -> MyPagePatternResponse.StepResponse.builder()
                        .stepOrder(s.getStepOrder())
                        .side(s.getSide().name())
                        .quantity(s.getQuantity().stripTrailingZeros().toPlainString())
                        .build())
                .toList();

        return MyPagePatternResponse.builder()
                .id(queue.getId())
                .symbol(queue.getSymbol())
                .sortOrder(queue.getSortOrder())
                .useYn(queue.getUseYn())
                .createdAt(queue.getCreatedAt().format(DT_FMT))
                .steps(steps)
                .build();
    }

    /**
     * TradeHistory 엔티티를 TradeHistoryResponse DTO로 변환한다.
     *
     * @param h TradeHistory 엔티티
     * @return 거래 히스토리 응답 DTO
     */
    private TradeHistoryResponse toTradeResponse(TradeHistory h) {
        return TradeHistoryResponse.builder()
                .id(h.getId())
                .symbol(h.getSymbol())
                .side(h.getSide().name())
                .quantity(h.getQuantity().stripTrailingZeros().toPlainString())
                .executedPrice(h.getExecutedPrice().stripTrailingZeros().toPlainString())
                .orderStatus(h.getOrderResult().name())
                .errorMessage(h.getErrorMessage())
                .createdAt(h.getCreatedAt().format(DT_FMT))
                .build();
    }
}
