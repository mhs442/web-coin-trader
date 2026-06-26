package com.coin.webcointrader.mypage.service;

import com.coin.webcointrader.autotrade.repository.InvestmentHistoryRepository;
import com.coin.webcointrader.autotrade.repository.PatternQueueRepository;
import com.coin.webcointrader.autotrade.repository.TradeHistoryRepository;
import com.coin.webcointrader.common.dto.response.PageResponse;
import com.coin.webcointrader.common.entity.*;
import com.coin.webcointrader.common.enums.TradeMode;
import com.coin.webcointrader.mypage.dto.*;
import com.coin.webcointrader.sim.repository.SimInvestmentHistoryRepository;
import com.coin.webcointrader.sim.repository.SimTradeHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 마이페이지 서비스.
 * 사용자의 패턴 큐 목록, 투자 히스토리, 차트 집계 데이터를 조회한다.
 */
@Service
@RequiredArgsConstructor
public class MyPageService {
    private final PatternQueueRepository patternQueueRepository;
    private final TradeHistoryRepository tradeHistoryRepository;
    private final InvestmentHistoryRepository investmentHistoryRepository;
    private final SimTradeHistoryRepository simTradeHistoryRepository;
    private final SimInvestmentHistoryRepository simInvestmentHistoryRepository;

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 사용자의 패턴 큐 목록을 페이징하여 조회한다.
     * 심볼 키워드 유무와 관계없이 DB 레벨에서 필터링 + 페이징한다.
     *
     * @param userId    사용자 ID
     * @param request   검색조건을 담은 객체
     * @return 페이징된 패턴 응답
     */
    public PageResponse<MyPagePatternResponse> getPatterns(Long userId, MyPagePatternRequest request) {
        Sort dbSort = buildSort("createdAt", request.getSort());
        TradeMode tradeMode = resolveMode(request.getMode());
        Pageable pageable = PageRequest.of(request.getPage(), request.getSize(), dbSort);

        Page<PatternQueue> queuePage;
        if (hasValue(request.getSymbol())) {
            // 심볼 키워드 있으면 DB 레벨 LIKE 필터로 페이징
            queuePage = patternQueueRepository.findByUserIdAndSymbolContainingIgnoreCaseAndTradeModeAndCreatedAtBetween(
                    userId, request.getSymbol(), tradeMode, request.getStartDate(), request.getEndDate(), pageable);
        } else {
            // 심볼 키워드 없으면 날짜 범위만으로 DB 페이징
            queuePage = patternQueueRepository.findByUserIdAndTradeModeAndCreatedAtBetween(
                    userId, tradeMode, request.getStartDate(), request.getEndDate(), pageable);
        }

        return PageResponse.from(queuePage, this::toPatternResponse);
    }

    /**
     * 사용자의 투자 히스토리를 페이징 조회하고, 합산 통계를 함께 반환한다.
     * 심볼 키워드가 있으면 DB 레벨 LIKE 필터로 페이징, 없으면 날짜 범위만으로 페이징.
     *
     * @param userId  사용자 ID
     * @param request 검색조건을 담은 객체
     * @return 페이징된 투자 히스토리 + 합산 통계 응답
     */
    public InvestmentHistoryPageResponse getInvestmentHistories(Long userId, InvestmentHistoryRequest request) {
        Sort dbSort = buildSort("createdAt", request.getSort());
        boolean isSim = resolveMode(request.getMode()) == TradeMode.SIM;

        // 모드에 따라 리포지토리 분기
        if (isSim) {
            return getSimInvestmentHistories(userId, request, dbSort);
        }

        PageResponse<InvestmentHistoryResponse> pageResponse;
        InvestmentSummaryResponse summary;

        if (hasValue(request.getSymbol())) {
            // 심볼 키워드 있으면 DB 레벨 LIKE 필터로 페이징
            Pageable pageable = PageRequest.of(request.getPage(), request.getSize(), dbSort);
            Page<InvestmentHistory> historyPage = investmentHistoryRepository
                    .findByUserIdAndSymbolContainingIgnoreCaseAndCreatedAtBetween(
                            userId, request.getSymbol(), request.getStartDate(), request.getEndDate(), pageable);

            pageResponse = PageResponse.from(historyPage, this::toInvestmentResponse);

            summary = buildSummaryWithStats(
                    investmentHistoryRepository.sumProfitLossByUserIdAndCreatedAtBetweenAndSymbol(
                            userId, request.getStartDate(), request.getEndDate(), request.getSymbol()),
                    investmentHistoryRepository.countWinLoss(userId, request.getStartDate(), request.getEndDate()));
        } else {
            // 심볼 키워드 없으면 날짜 범위만으로 DB 페이징
            Pageable pageable = PageRequest.of(request.getPage(), request.getSize(), dbSort);
            Page<InvestmentHistory> historyPage = investmentHistoryRepository.findByUserIdAndCreatedAtBetween(
                    userId, request.getStartDate(), request.getEndDate(), pageable);

            pageResponse = PageResponse.from(historyPage, this::toInvestmentResponse);

            summary = buildSummaryWithStats(
                    investmentHistoryRepository.sumProfitLossByUserIdAndCreatedAtBetween(
                            userId, request.getStartDate(), request.getEndDate()),
                    investmentHistoryRepository.countWinLoss(userId, request.getStartDate(), request.getEndDate()));
        }

        return InvestmentHistoryPageResponse.builder()
                .page(pageResponse)
                .summary(summary)
                .build();
    }

    /**
     * 투자 1건(investmentId)에 해당하는 거래 내역만 조회한다.
     *
     * <p>PatternStep.id는 큐 사이클이 반복돼도 재사용되므로, 같은 단계의 모든 거래를
     * queueStepId로 조회하면 여러 사이클의 거래가 섞인다.
     * 해결책: 투자 히스토리의 createdAt을 상한선으로 설정하고, 최신순 DESC로 조회한 뒤
     * EXIT(SELL/LIQUIDATION) 1건 + 그 직전 ENTRY 1건만 추출해 해당 사이클 거래를 특정한다.</p>
     *
     * @param userId        사용자 ID
     * @param investmentId  InvestmentHistory.id (투자 PK)
     * @param mode          거래 모드 ("sim" 또는 그 외)
     * @return 거래 내역 목록 (체결 일시 오름차순, 해당 사이클 거래만)
     */
    public List<InvestmentTradeDetailResponse> getInvestmentTradeDetails(Long userId, Long investmentId, String mode) {
        if ("sim".equalsIgnoreCase(mode)) {
            SimInvestmentHistory inv = simInvestmentHistoryRepository
                    .findByIdAndUserId(investmentId, userId)
                    .orElse(null);
            if (inv == null) return List.of();

            List<com.coin.webcointrader.common.entity.SimTradeHistory> trades =
                    simTradeHistoryRepository.findByQueueStepIdAndUserIdAndCreatedAtLessThanEqualOrderByCreatedAtDesc(
                            inv.getPatternStepId(), userId, inv.getCreatedAt());

            return extractCycleTrades(trades).stream()
                    .map(this::toSimTradeDetailResponse)
                    .toList();
        }

        InvestmentHistory inv = investmentHistoryRepository
                .findByIdAndUserId(investmentId, userId)
                .orElse(null);
        if (inv == null) return List.of();

        List<com.coin.webcointrader.common.entity.TradeHistory> trades =
                tradeHistoryRepository.findByQueueStepIdAndUserIdAndCreatedAtLessThanEqualOrderByCreatedAtDesc(
                        inv.getPatternStepId(), userId, inv.getCreatedAt());

        return extractCycleTrades(trades).stream()
                .map(this::toTradeDetailResponse)
                .toList();
    }

    /**
     * 최신순 거래 목록에서 해당 사이클(EXIT + 직전 ENTRY)을 추출하여 오름차순으로 반환한다.
     * EXIT(sell/liquidation)을 먼저 찾고, 그 직전 ENTRY를 찾아 쌍으로 구성한다.
     *
     * @param trades 최신순 거래 목록 (createdAt DESC)
     * @return 해당 사이클 거래 목록 (createdAt ASC)
     */
    @SuppressWarnings("unchecked")
    private <T> List<T> extractCycleTrades(List<T> trades) {
        if (trades.isEmpty()) return List.of();

        // EXIT(sell/liquidation) 찾기 — 최신순이므로 첫 번째가 해당 사이클 EXIT
        T exitTrade = null;
        int exitIdx = -1;
        for (int i = 0; i < trades.size(); i++) {
            String orderType = getOrderType(trades.get(i));
            if ("sell".equalsIgnoreCase(orderType) || "liquidation".equalsIgnoreCase(orderType)) {
                exitTrade = trades.get(i);
                exitIdx = i;
                break;
            }
        }
        if (exitTrade == null) return List.of();

        // EXIT 직후(DESC 기준)가 해당 사이클의 ENTRY
        List<T> result = new java.util.ArrayList<>();
        for (int i = exitIdx + 1; i < trades.size(); i++) {
            if ("entry".equalsIgnoreCase(getOrderType(trades.get(i)))) {
                result.add(trades.get(i)); // ENTRY
                break;
            }
        }
        result.add(exitTrade); // EXIT
        // ASC 순서로 반환 (ENTRY → EXIT)
        return result;
    }

    /** 거래 엔티티에서 orderType 문자열을 추출한다. */
    private String getOrderType(Object trade) {
        if (trade instanceof com.coin.webcointrader.common.entity.TradeHistory t) return t.getOrderType();
        if (trade instanceof com.coin.webcointrader.common.entity.SimTradeHistory t) return t.getOrderType();
        return "";
    }

    /**
     * 히스토리 차트용 집계 데이터를 조회한다.
     * 일별 손익 / 심볼별 손익 / 승패 카운트 3종을 반환한다.
     *
     * @param userId  사용자 ID
     * @param request 검색조건을 담은 객체
     * @return 차트 데이터 응답
     */
    public HistoryChartResponse getHistoryChart(Long userId, InvestmentHistoryRequest request) {
        boolean isSim = resolveMode(request.getMode()) == TradeMode.SIM;
        boolean hasSymbol = request.getSymbol() != null && !request.getSymbol().isBlank();

        LocalDateTime start = request.getStartDate();
        LocalDateTime end = request.getEndDate();
        String symbol = request.getSymbol();

        List<Object[]> dailyRaw;
        List<Object[]> symbolRaw;
        List<Object[]> winLossRaw;

        // 모드 + 심볼 필터 여부에 따라 리포지토리 분기
        if (isSim) {
            if (hasSymbol) {
                dailyRaw  = simInvestmentHistoryRepository.findDailyProfitLossBySymbol(userId, start, end, symbol);
                symbolRaw = simInvestmentHistoryRepository.findProfitLossBySymbolFiltered(userId, start, end, symbol);
                winLossRaw = simInvestmentHistoryRepository.countWinLossBySymbol(userId, start, end, symbol);
            } else {
                dailyRaw  = simInvestmentHistoryRepository.findDailyProfitLoss(userId, start, end);
                symbolRaw = simInvestmentHistoryRepository.findProfitLossBySymbol(userId, start, end);
                winLossRaw = simInvestmentHistoryRepository.countWinLoss(userId, start, end);
            }
        } else {
            if (hasSymbol) {
                dailyRaw  = investmentHistoryRepository.findDailyProfitLossBySymbol(userId, start, end, symbol);
                symbolRaw = investmentHistoryRepository.findProfitLossBySymbolFiltered(userId, start, end, symbol);
                winLossRaw = investmentHistoryRepository.countWinLossBySymbol(userId, start, end, symbol);
            } else {
                dailyRaw  = investmentHistoryRepository.findDailyProfitLoss(userId, start, end);
                symbolRaw = investmentHistoryRepository.findProfitLossBySymbol(userId, start, end);
                winLossRaw = investmentHistoryRepository.countWinLoss(userId, start, end);
            }
        }

        // 일별 데이터 변환
        List<HistoryChartResponse.DailyData> dailyData = dailyRaw.stream()
                .map(row -> HistoryChartResponse.DailyData.builder()
                        .date((String) row[0])
                        .profitLoss(((BigDecimal) row[1]).stripTrailingZeros().toPlainString())
                        .build())
                .toList();

        // 심볼별 데이터 변환
        List<HistoryChartResponse.SymbolData> symbolData = symbolRaw.stream()
                .map(row -> HistoryChartResponse.SymbolData.builder()
                        .symbol((String) row[0])
                        .profitLoss(((BigDecimal) row[1]).stripTrailingZeros().toPlainString())
                        .build())
                .toList();

        // 승패 카운트 변환
        Object[] winLoss = winLossRaw.isEmpty() ? new Object[]{0L, 0L, 0L} : winLossRaw.get(0);
        long winCount = winLoss[0] != null ? ((Number) winLoss[0]).longValue() : 0L;
        long lossCount = winLoss[1] != null ? ((Number) winLoss[1]).longValue() : 0L;
        long totalCount = winLoss[2] != null ? ((Number) winLoss[2]).longValue() : 0L;

        return HistoryChartResponse.builder()
                .dailyData(dailyData)
                .symbolData(symbolData)
                .winCount(winCount)
                .lossCount(lossCount)
                .totalCount(totalCount)
                .build();
    }

    /**
     * 선택한 거래 히스토리 ID 목록을 삭제한다.
     *
     * @param userId 사용자 ID
     * @param ids    삭제할 거래 히스토리 ID 목록
     * @param mode   거래 모드 ("sim" 또는 그 외)
     */
    @org.springframework.transaction.annotation.Transactional
    public void deleteTradeHistories(Long userId, List<Long> ids, String mode) {
        // 모드에 따라 리포지토리 분기
        if ("sim".equalsIgnoreCase(mode)) {
            simTradeHistoryRepository.deleteAllByIdInAndUserId(ids, userId);
        } else {
            tradeHistoryRepository.deleteAllByIdInAndUserId(ids, userId);
        }
    }

    /**
     * 사용자의 전체 거래 히스토리를 삭제한다.
     *
     * @param userId 사용자 ID
     * @param mode   거래 모드 ("sim" 또는 그 외)
     */
    @org.springframework.transaction.annotation.Transactional
    public void deleteAllTradeHistories(Long userId, String mode) {
        // 모드에 따라 리포지토리 분기
        if ("sim".equalsIgnoreCase(mode)) {
            simTradeHistoryRepository.deleteAllByUserId(userId);
        } else {
            tradeHistoryRepository.deleteAllByUserId(userId);
        }
    }

    /**
     * 선택한 투자 히스토리 ID 목록을 삭제한다.
     *
     * @param userId 사용자 ID
     * @param ids    삭제할 투자 히스토리 ID 목록
     * @param mode   거래 모드 ("sim" 또는 그 외)
     */
    @org.springframework.transaction.annotation.Transactional
    public void deleteInvestmentHistories(Long userId, List<Long> ids, String mode) {
        // 모드에 따라 리포지토리 분기
        if ("sim".equalsIgnoreCase(mode)) {
            simInvestmentHistoryRepository.deleteAllByIdInAndUserId(ids, userId);
        } else {
            investmentHistoryRepository.deleteAllByIdInAndUserId(ids, userId);
        }
    }

    /**
     * 사용자의 전체 투자 히스토리를 삭제한다.
     *
     * @param userId 사용자 ID
     * @param mode   거래 모드 ("sim" 또는 그 외)
     */
    @org.springframework.transaction.annotation.Transactional
    public void deleteAllInvestmentHistories(Long userId, String mode) {
        // 모드에 따라 리포지토리 분기
        if ("sim".equalsIgnoreCase(mode)) {
            simInvestmentHistoryRepository.deleteAllByUserId(userId);
        } else {
            investmentHistoryRepository.deleteAllByUserId(userId);
        }
    }

    // ─────────────────────────────────────────────
    // private 헬퍼 메서드
    // ─────────────────────────────────────────────

    /**
     * 모의투자 투자 히스토리를 조회한다.
     */
    private InvestmentHistoryPageResponse getSimInvestmentHistories(Long userId, InvestmentHistoryRequest request, Sort dbSort) {
        PageResponse<InvestmentHistoryResponse> pageResponse;
        InvestmentSummaryResponse summary;

        if (hasValue(request.getSymbol())) {
            Pageable pageable = PageRequest.of(request.getPage(), request.getSize(), dbSort);
            Page<SimInvestmentHistory> historyPage = simInvestmentHistoryRepository
                    .findByUserIdAndSymbolContainingIgnoreCaseAndCreatedAtBetween(
                            userId, request.getSymbol(), request.getStartDate(), request.getEndDate(), pageable);

            pageResponse = PageResponse.from(historyPage, this::toSimInvestmentResponse);

            summary = buildSummaryWithStats(
                    simInvestmentHistoryRepository.sumProfitLossByUserIdAndCreatedAtBetweenAndSymbol(
                            userId, request.getStartDate(), request.getEndDate(), request.getSymbol()),
                    simInvestmentHistoryRepository.countWinLoss(userId, request.getStartDate(), request.getEndDate()));
        } else {
            Pageable pageable = PageRequest.of(request.getPage(), request.getSize(), dbSort);
            Page<SimInvestmentHistory> historyPage = simInvestmentHistoryRepository.findByUserIdAndCreatedAtBetween(
                    userId, request.getStartDate(), request.getEndDate(), pageable);

            pageResponse = PageResponse.from(historyPage, this::toSimInvestmentResponse);

            summary = buildSummaryWithStats(
                    simInvestmentHistoryRepository.sumProfitLossByUserIdAndCreatedAtBetween(
                            userId, request.getStartDate(), request.getEndDate()),
                    simInvestmentHistoryRepository.countWinLoss(userId, request.getStartDate(), request.getEndDate()));
        }

        return InvestmentHistoryPageResponse.builder()
                .page(pageResponse)
                .summary(summary)
                .build();
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
     * DB SUM 쿼리 결과 + 승패 카운트 결과로 합산 통계 응답을 생성한다.
     *
     * @param profitLossResult List containing single Object[] { totalProfit, totalLoss }
     * @param winLossResult    List containing single Object[] { winCount, lossCount, totalCount }
     * @return 합산 통계 응답 DTO
     */
    private InvestmentSummaryResponse buildSummaryWithStats(List<Object[]> profitLossResult, List<Object[]> winLossResult) {
        Object[] pl = profitLossResult.get(0);
        BigDecimal totalProfit = (BigDecimal) pl[0];
        BigDecimal totalLoss = (BigDecimal) pl[1];
        BigDecimal netTotal = totalProfit.add(totalLoss);

        Object[] wl = winLossResult.isEmpty() ? new Object[]{0L, 0L, 0L} : winLossResult.get(0);
        long winCount = wl[0] != null ? ((Number) wl[0]).longValue() : 0L;
        long lossCount = wl[1] != null ? ((Number) wl[1]).longValue() : 0L;
        long totalCount = wl[2] != null ? ((Number) wl[2]).longValue() : 0L;

        // 승률 계산 (totalCount가 0이면 0.0%)
        String winRate = totalCount > 0
                ? new BigDecimal(winCount * 100).divide(new BigDecimal(totalCount), 1, RoundingMode.HALF_UP).toPlainString()
                : "0.0";

        return InvestmentSummaryResponse.builder()
                .totalProfit(totalProfit.stripTrailingZeros().toPlainString())
                .totalLoss(totalLoss.stripTrailingZeros().toPlainString())
                .netTotal(netTotal.stripTrailingZeros().toPlainString())
                .totalCount(totalCount)
                .winCount(winCount)
                .winRate(winRate)
                .build();
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
     * 문자열 모드를 TradeMode 열거형으로 변환한다.
     *
     * @param mode 모드 문자열 ("sim" 또는 그 외)
     * @return TradeMode 열거형 (기본값 MAIN)
     */
    private TradeMode resolveMode(String mode) {
        return "sim".equalsIgnoreCase(mode) ? TradeMode.SIM : TradeMode.MAIN;
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
     * InvestmentHistory → InvestmentHistoryResponse 변환
     *
     * @param h InvestmentHistory 엔티티
     * @return 투자 히스토리 응답 DTO
     */
    private InvestmentHistoryResponse toInvestmentResponse(InvestmentHistory h) {
        return InvestmentHistoryResponse.builder()
                .id(h.getId())
                .symbol(h.getSymbol())
                .side(h.getSide().name())
                .entryPrice(h.getEntryPrice().stripTrailingZeros().toPlainString())
                .exitPrice(h.getExitPrice().stripTrailingZeros().toPlainString())
                .amount(h.getAmount().stripTrailingZeros().toPlainString())
                .leverage(h.getLeverage())
                .tpPrice(h.getTpPrice() != null ? h.getTpPrice().stripTrailingZeros().toPlainString() : null)
                .slPrice(h.getSlPrice() != null ? h.getSlPrice().stripTrailingZeros().toPlainString() : null)
                .profitLoss(h.getProfitLoss().stripTrailingZeros().toPlainString())
                .patternStepId(h.getPatternStepId())
                .createdAt(h.getCreatedAt().format(DT_FMT))
                .build();
    }

    /**
     * TradeHistory → InvestmentTradeDetailResponse 변환
     *
     * @param h TradeHistory 엔티티
     * @return 거래 상세 응답 DTO
     */
    private InvestmentTradeDetailResponse toTradeDetailResponse(TradeHistory h) {
        return InvestmentTradeDetailResponse.builder()
                .id(h.getId())
                .orderType(h.getOrderType())
                .executedPrice(h.getExecutedPrice().stripTrailingZeros().toPlainString())
                .amount(h.getAmount().stripTrailingZeros().toPlainString())
                .fee(h.getFee() != null ? h.getFee().stripTrailingZeros().toPlainString() : null)
                .orderStatus(h.getOrderResult().name())
                .errorMessage(h.getErrorMessage())
                .createdAt(h.getCreatedAt().format(DT_FMT))
                .build();
    }

    /**
     * SimInvestmentHistory → InvestmentHistoryResponse 변환
     *
     * @param h SimInvestmentHistory 엔티티
     * @return 투자 히스토리 응답 DTO
     */
    private InvestmentHistoryResponse toSimInvestmentResponse(SimInvestmentHistory h) {
        return InvestmentHistoryResponse.builder()
                .id(h.getId())
                .symbol(h.getSymbol())
                .side(h.getSide().name())
                .entryPrice(h.getEntryPrice().stripTrailingZeros().toPlainString())
                .exitPrice(h.getExitPrice().stripTrailingZeros().toPlainString())
                .amount(h.getAmount().stripTrailingZeros().toPlainString())
                .leverage(h.getLeverage())
                .tpPrice(h.getTpPrice() != null ? h.getTpPrice().stripTrailingZeros().toPlainString() : null)
                .slPrice(h.getSlPrice() != null ? h.getSlPrice().stripTrailingZeros().toPlainString() : null)
                .profitLoss(h.getProfitLoss().stripTrailingZeros().toPlainString())
                .patternStepId(h.getPatternStepId())
                .createdAt(h.getCreatedAt().format(DT_FMT))
                .build();
    }

    /**
     * SimTradeHistory → InvestmentTradeDetailResponse 변환
     *
     * @param h SimTradeHistory 엔티티
     * @return 거래 상세 응답 DTO
     */
    private InvestmentTradeDetailResponse toSimTradeDetailResponse(SimTradeHistory h) {
        return InvestmentTradeDetailResponse.builder()
                .id(h.getId())
                .orderType(h.getOrderType())
                .executedPrice(h.getExecutedPrice().stripTrailingZeros().toPlainString())
                .amount(h.getAmount().stripTrailingZeros().toPlainString())
                .fee(h.getFee() != null ? h.getFee().stripTrailingZeros().toPlainString() : null)
                .orderStatus(h.getOrderResult().name())
                .errorMessage(h.getErrorMessage())
                .createdAt(h.getCreatedAt().format(DT_FMT))
                .build();
    }
}
