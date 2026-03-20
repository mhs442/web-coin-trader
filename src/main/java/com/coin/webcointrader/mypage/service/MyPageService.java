package com.coin.webcointrader.mypage.service;

import com.coin.webcointrader.autotrade.repository.PatternQueueRepository;
import com.coin.webcointrader.autotrade.repository.TradeHistoryRepository;
import com.coin.webcointrader.common.dto.response.PageResponse;
import com.coin.webcointrader.common.entity.Pattern;
import com.coin.webcointrader.common.entity.PatternQueue;
import com.coin.webcointrader.common.entity.TradeHistory;
import com.coin.webcointrader.mypage.dto.MyPagePatternRequest;
import com.coin.webcointrader.mypage.dto.MyPagePatternResponse;
import com.coin.webcointrader.mypage.dto.TradeHistoryRequest;
import com.coin.webcointrader.mypage.dto.TradeHistoryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 마이페이지 서비스.
 * 사용자의 패턴 큐 목록과 거래 히스토리를 날짜 범위·심볼 키워드 조건으로 조회한다.
 */
@Service
@RequiredArgsConstructor
public class MyPageService {
    private final PatternQueueRepository patternQueueRepository;
    private final TradeHistoryRepository tradeHistoryRepository;

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 사용자의 패턴 큐 목록을 페이징하여 조회한다.
     * 심볼 키워드가 없으면 DB 페이징, 있으면 전체 조회 후 Java 필터 + 수동 페이징.
     *
     * @param userId    사용자 ID
     * @param request   검색조건을 담은 객체
     * @return 페이징된 패턴 응답
     */
    public PageResponse<MyPagePatternResponse> getPatterns(Long userId, MyPagePatternRequest request) {
        Sort dbSort = buildSort("createdAt", request.getSort());

        // 심볼 키워드가 있으면 전체 조회 후 Java 필터 + 수동 페이징
        if (hasValue(request.getSymbol())) {
            List<PatternQueue> queues;
            queues = patternQueueRepository.findByUserIdAndCreatedAtBetween(userId, request.getStartDate(), request.getEndDate(), dbSort);

            String keyword = request.getSymbol().toUpperCase();
            List<MyPagePatternResponse> filtered = queues.stream()
                    .filter(q -> q.getSymbol().toUpperCase().contains(keyword))
                    .map(this::toPatternResponse)
                    .toList();

            return PageResponse.fromList(filtered, request.getPage(), request.getSize());
        }

        // 심볼 키워드 없으면 DB 페이징 사용
        Pageable pageable = PageRequest.of(request.getPage(), request.getSize(), dbSort);
        Page<PatternQueue> queuePage;
        queuePage = patternQueueRepository.findByUserIdAndCreatedAtBetween(userId, request.getStartDate(), request.getEndDate(), pageable);
        return PageResponse.from(queuePage, this::toPatternResponse);
    }

    /**
     * 사용자의 거래 히스토리를 페이징하여 조회한다.
     * 심볼 키워드가 없으면 DB 페이징, 있으면 전체 조회 후 Java 필터 + 수동 페이징.
     *
     * @param userId  사용자 ID
     * @param request 검색조건을 담은 객체
     * @return 페이징된 거래 히스토리 응답
     */
    public PageResponse<TradeHistoryResponse> getTradeHistories(Long userId, TradeHistoryRequest request) {
        Sort dbSort = buildSort("createdAt", request.getSort());

        // 심볼 키워드가 있으면 전체 조회 후 Java 필터 + 수동 페이징
        if (hasValue(request.getSymbol())) {
            List<TradeHistory> histories = tradeHistoryRepository.findByUserIdAndCreatedAtBetween(userId, request.getStartDate(), request.getEndDate(), dbSort);

            // 심볼 키워드 %LIKE% 사용 없이 자바단에서 필터링 -> index사용 불가
            String keyword = request.getSymbol().toUpperCase();
            List<TradeHistoryResponse> filtered = histories.stream()
                    .filter(h -> h.getSymbol().toUpperCase().contains(keyword))
                    .map(this::toTradeResponse)
                    .toList();

            return PageResponse.fromList(filtered, request.getPage(), request.getSize());
        }

        // 심볼 키워드 없으면 DB 페이징 사용
        Pageable pageable = PageRequest.of(request.getPage(), request.getSize(), dbSort);
        Page<TradeHistory> historyPage = tradeHistoryRepository.findByUserIdAndCreatedAtBetween(userId, request.getStartDate(), request.getEndDate(), pageable);

        return PageResponse.from(historyPage, this::toTradeResponse);
    }

    // ─────────────────────────────────────────────
    // 유틸 메서드
    // ─────────────────────────────────────────────

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

    // ─────────────────────────────────────────────
    // 변환 메서드
    // ─────────────────────────────────────────────

    /**
     * PatternQueue → MyPagePatternResponse 변환 (계층 구조 포함)
     *
     * @param queue PatternQueue 엔티티
     * @return 마이페이지 패턴 응답 DTO
     */
    private MyPagePatternResponse toPatternResponse(PatternQueue queue) {
        List<MyPagePatternResponse.StepResponse> steps = queue.getSteps().stream()
                .map(step -> MyPagePatternResponse.StepResponse.builder()
                        .stepLevel(step.getStepLevel())
                        .patterns(step.getPatterns().stream()
                                .map(this::toPatternDetailResponse)
                                .toList())
                        .build())
                .toList();

        return MyPagePatternResponse.builder()
                .id(queue.getId())
                .symbol(queue.getSymbol())
                .active(queue.isActive())
                .triggerSeconds(queue.getTriggerSeconds())
                .triggerRate(queue.getTriggerRate())
                .createdAt(queue.getCreatedAt().format(DT_FMT))
                .steps(steps)
                .build();
    }

    /**
     * Pattern → PatternResponse 변환 (블록 포함)
     *
     * @param pattern Pattern 엔티티
     * @return 패턴 응답 DTO
     */
    private MyPagePatternResponse.PatternResponse toPatternDetailResponse(Pattern pattern) {
        List<MyPagePatternResponse.BlockResponse> blocks = pattern.getBlocks().stream()
                .map(block -> MyPagePatternResponse.BlockResponse.builder()
                        .side(block.getSide().name())
                        .blockOrder(block.getBlockOrder())
                        .isLeaf(block.isLeaf())
                        .build())
                .toList();

        return MyPagePatternResponse.PatternResponse.builder()
                .patternOrder(pattern.getPatternOrder())
                .amount(pattern.getAmount())
                .leverage(pattern.getLeverage())
                .stopLossRate(pattern.getStopLossRate())
                .takeProfitRate(pattern.getTakeProfitRate())
                .blocks(blocks)
                .build();
    }

    /**
     * TradeHistory → TradeHistoryResponse 변환
     *
     * @param h TradeHistory 엔티티
     * @return 거래 히스토리 응답 DTO
     */
    private TradeHistoryResponse toTradeResponse(TradeHistory h) {
        return TradeHistoryResponse.builder()
                .id(h.getId())
                .symbol(h.getSymbol())
                .side(h.getSide().name())
                .amount(h.getAmount().stripTrailingZeros().toPlainString())
                .executedPrice(h.getExecutedPrice().stripTrailingZeros().toPlainString())
                .orderStatus(h.getOrderResult().name())
                .errorMessage(h.getErrorMessage())
                .createdAt(h.getCreatedAt().format(DT_FMT))
                .build();
    }
}
