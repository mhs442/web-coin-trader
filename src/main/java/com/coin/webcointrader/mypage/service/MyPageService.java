package com.coin.webcointrader.mypage.service;

import com.coin.webcointrader.autotrade.dto.PatternQueueDTO;
import com.coin.webcointrader.autotrade.service.PatternQueueService;
import com.coin.webcointrader.common.dto.response.PageResponse;
import com.coin.webcointrader.common.enums.TradeMode;
import com.coin.webcointrader.mypage.dto.*;
import com.coin.webcointrader.trade.dto.DailyProfitLossDTO;
import com.coin.webcointrader.trade.dto.InvestmentHistoryDTO;
import com.coin.webcointrader.trade.dto.ProfitLossSummaryDTO;
import com.coin.webcointrader.trade.dto.SymbolProfitLossDTO;
import com.coin.webcointrader.trade.dto.TradeHistoryDTO;
import com.coin.webcointrader.trade.dto.WinLossCountDTO;
import com.coin.webcointrader.trade.service.HistoryService;
import com.coin.webcointrader.trade.service.TradeFacade;
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
    private final PatternQueueService patternQueueService;
    private final TradeFacade tradeFacade; // 모드별 HistoryService 선택 (MAIN/SIM)

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

        // 심볼 필터/날짜 범위 분기와 엔티티→DTO 변환은 autotrade 도메인 서비스가 담당
        Page<PatternQueueDTO> queuePage = patternQueueService.findQueuePage(
                userId, request.getSymbol(), tradeMode, request.getStartDate(), request.getEndDate(), pageable);

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
        HistoryService history = historyService(request.getMode());
        Pageable pageable = PageRequest.of(request.getPage(), request.getSize(), dbSort);

        // 심볼 키워드가 있으면 DB 레벨 LIKE 필터, 없으면 날짜 범위만으로 페이징 (분기는 HistoryService 내부)
        Page<InvestmentHistoryDTO> historyPage = history.findInvestments(
                userId, request.getSymbol(), request.getStartDate(), request.getEndDate(), pageable);
        PageResponse<InvestmentHistoryResponse> pageResponse = PageResponse.from(historyPage, this::toInvestmentResponse);

        // 합산: 심볼 필터 반영. 승패 카운트는 기존 동작대로 심볼 필터 없이 집계
        InvestmentSummaryResponse summary = buildSummaryWithStats(
                history.sumProfitLoss(userId, request.getSymbol(), request.getStartDate(), request.getEndDate()),
                history.countWinLoss(userId, request.getStartDate(), request.getEndDate()));

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
        HistoryService history = historyService(mode);

        InvestmentHistoryDTO inv = history.findInvestment(investmentId, userId).orElse(null);
        if (inv == null) return List.of();

        List<TradeHistoryDTO> trades = history.findTradesUpTo(inv.getPatternStepId(), userId, inv.getCreatedAt());

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
    private List<TradeHistoryDTO> extractCycleTrades(List<TradeHistoryDTO> trades) {
        if (trades.isEmpty()) return List.of();

        // EXIT(sell/liquidation) 찾기 — 최신순이므로 첫 번째가 해당 사이클 EXIT
        TradeHistoryDTO exitTrade = null;
        int exitIdx = -1;
        for (int i = 0; i < trades.size(); i++) {
            String orderType = trades.get(i).getOrderType();
            if ("sell".equalsIgnoreCase(orderType) || "liquidation".equalsIgnoreCase(orderType)) {
                exitTrade = trades.get(i);
                exitIdx = i;
                break;
            }
        }
        if (exitTrade == null) return List.of();

        // EXIT 직후(DESC 기준)가 해당 사이클의 ENTRY
        List<TradeHistoryDTO> result = new java.util.ArrayList<>();
        for (int i = exitIdx + 1; i < trades.size(); i++) {
            if ("entry".equalsIgnoreCase(trades.get(i).getOrderType())) {
                result.add(trades.get(i)); // ENTRY
                break;
            }
        }
        result.add(exitTrade); // EXIT
        // ASC 순서로 반환 (ENTRY → EXIT)
        return result;
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
        HistoryService history = historyService(request.getMode());
        LocalDateTime start = request.getStartDate();
        LocalDateTime end = request.getEndDate();
        String symbol = request.getSymbol();

        List<DailyProfitLossDTO> dailyRaw = history.findDailyProfitLoss(userId, symbol, start, end);
        List<SymbolProfitLossDTO> symbolRaw = history.findProfitLossBySymbol(userId, symbol, start, end);
        // 심볼 필터가 있으면 심볼 기준 승패 카운트, 없으면 전체 카운트
        WinLossCountDTO winLoss = hasValue(symbol)
                ? history.countWinLossBySymbol(userId, symbol, start, end)
                : history.countWinLoss(userId, start, end);

        // 일별 데이터 변환
        List<HistoryChartResponse.DailyData> dailyData = dailyRaw.stream()
                .map(d -> HistoryChartResponse.DailyData.builder()
                        .date(d.getDate())
                        .profitLoss(d.getProfitLoss().stripTrailingZeros().toPlainString())
                        .build())
                .toList();

        // 심볼별 데이터 변환
        List<HistoryChartResponse.SymbolData> symbolData = symbolRaw.stream()
                .map(d -> HistoryChartResponse.SymbolData.builder()
                        .symbol(d.getSymbol())
                        .profitLoss(d.getProfitLoss().stripTrailingZeros().toPlainString())
                        .build())
                .toList();

        return HistoryChartResponse.builder()
                .dailyData(dailyData)
                .symbolData(symbolData)
                .winCount(winLoss.getWinCount())
                .lossCount(winLoss.getLossCount())
                .totalCount(winLoss.getTotalCount())
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
        // 모드에 따라 MAIN/SIM 히스토리 서비스 분기
        historyService(mode).deleteTrades(ids, userId);
    }

    /**
     * 사용자의 전체 거래 히스토리를 삭제한다.
     *
     * @param userId 사용자 ID
     * @param mode   거래 모드 ("sim" 또는 그 외)
     */
    @org.springframework.transaction.annotation.Transactional
    public void deleteAllTradeHistories(Long userId, String mode) {
        // 모드에 따라 MAIN/SIM 히스토리 서비스 분기
        historyService(mode).deleteAllTrades(userId);
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
        // 모드에 따라 MAIN/SIM 히스토리 서비스 분기
        historyService(mode).deleteInvestments(ids, userId);
    }

    /**
     * 사용자의 전체 투자 히스토리를 삭제한다.
     *
     * @param userId 사용자 ID
     * @param mode   거래 모드 ("sim" 또는 그 외)
     */
    @org.springframework.transaction.annotation.Transactional
    public void deleteAllInvestmentHistories(Long userId, String mode) {
        // 모드에 따라 MAIN/SIM 히스토리 서비스 분기
        historyService(mode).deleteAllInvestments(userId);
    }

    // ─────────────────────────────────────────────
    // private 헬퍼 메서드
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
     * 손익 합계 + 승패 카운트로 합산 통계 응답을 생성한다.
     *
     * @param pl 수익 합 / 손실 합
     * @param wl 승 / 패 / 전체 건수
     * @return 합산 통계 응답 DTO
     */
    private InvestmentSummaryResponse buildSummaryWithStats(ProfitLossSummaryDTO pl, WinLossCountDTO wl) {
        BigDecimal totalProfit = pl.getTotalProfit();
        BigDecimal totalLoss = pl.getTotalLoss();
        BigDecimal netTotal = totalProfit.add(totalLoss);

        long winCount = wl.getWinCount();
        long totalCount = wl.getTotalCount();

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

    /**
     * 모드 문자열에 해당하는 히스토리 서비스를 반환한다.
     *
     * @param mode 모드 문자열 ("sim" 또는 그 외)
     * @return SIM이면 모의투자, 그 외는 실전 히스토리 서비스
     */
    private HistoryService historyService(String mode) {
        return tradeFacade.historyService(resolveMode(mode));
    }

    // ─────────────────────────────────────────────
    // 변환 메서드
    // ─────────────────────────────────────────────

    /**
     * PatternQueueDTO → MyPagePatternResponse 변환 (계층 구조 포함)
     *
     * @param queue 패턴 큐 DTO
     * @return 마이페이지 패턴 응답 DTO
     */
    private MyPagePatternResponse toPatternResponse(PatternQueueDTO queue) {
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
                .exchangeType(queue.getExchangeType())
                .active(queue.isActive())
                .triggerRate(queue.getTriggerRate())
                .createdAt(queue.getCreatedAt().format(DT_FMT))
                .steps(steps)
                .build();
    }

    /**
     * PatternDTO → PatternResponse 변환 (블록 포함)
     *
     * @param pattern 패턴 DTO
     * @return 패턴 응답 DTO
     */
    private MyPagePatternResponse.PatternResponse toPatternDetailResponse(PatternQueueDTO.PatternDTO pattern) {
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
     * InvestmentHistoryDTO → InvestmentHistoryResponse 변환
     *
     * @param h 투자 히스토리 DTO
     * @return 투자 히스토리 응답 DTO
     */
    private InvestmentHistoryResponse toInvestmentResponse(InvestmentHistoryDTO h) {
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
                .fundingFee(h.getFundingFee() != null ? h.getFundingFee().stripTrailingZeros().toPlainString() : null) // SIM은 null
                .patternStepId(h.getPatternStepId())
                .createdAt(h.getCreatedAt().format(DT_FMT))
                .build();
    }

    /**
     * TradeHistoryDTO → InvestmentTradeDetailResponse 변환
     *
     * @param h 거래 히스토리 DTO
     * @return 거래 상세 응답 DTO
     */
    private InvestmentTradeDetailResponse toTradeDetailResponse(TradeHistoryDTO h) {
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
