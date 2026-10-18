package com.coin.webcointrader.sim.service;

import com.coin.webcointrader.sim.entity.SimInvestmentHistory;
import com.coin.webcointrader.sim.entity.SimTradeHistory;
import com.coin.webcointrader.sim.repository.SimInvestmentHistoryRepository;
import com.coin.webcointrader.sim.repository.SimTradeHistoryRepository;
import com.coin.webcointrader.trade.dto.DailyProfitLossDTO;
import com.coin.webcointrader.trade.dto.InvestmentHistoryDTO;
import com.coin.webcointrader.trade.dto.ProfitLossSummaryDTO;
import com.coin.webcointrader.trade.dto.SymbolProfitLossDTO;
import com.coin.webcointrader.trade.dto.TradeHistoryDTO;
import com.coin.webcointrader.trade.dto.WinLossCountDTO;
import com.coin.webcointrader.trade.service.HistoryAggregateMapper;
import com.coin.webcointrader.trade.service.HistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * SIM(모의투자) 거래/투자 히스토리 서비스.
 * sim_trade_history / sim_investment_history 테이블을 소유하며, 외부에는 DTO만 노출한다.
 * SIM에는 펀딩비가 없으므로 DTO의 fundingFee는 항상 null이다.
 */
@Service
@RequiredArgsConstructor
public class SimHistoryService implements HistoryService {

    private final SimTradeHistoryRepository simTradeHistoryRepository;
    private final SimInvestmentHistoryRepository simInvestmentHistoryRepository;

    @Override
    @Transactional
    public void saveTrade(TradeHistoryDTO dto) {
        simTradeHistoryRepository.save(toEntity(dto));
    }

    @Override
    @Transactional
    public void saveInvestment(InvestmentHistoryDTO dto) {
        simInvestmentHistoryRepository.save(toEntity(dto));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<InvestmentHistoryDTO> findInvestments(Long userId, String symbol,
                                                      LocalDateTime start, LocalDateTime end, Pageable pageable) {
        // 심볼 키워드가 있으면 DB 레벨 부분일치 필터, 없으면 기간만 조회
        Page<SimInvestmentHistory> page = hasValue(symbol)
                ? simInvestmentHistoryRepository.findByUserIdAndSymbolContainingIgnoreCaseAndCreatedAtBetween(
                        userId, symbol, start, end, pageable)
                : simInvestmentHistoryRepository.findByUserIdAndCreatedAtBetween(userId, start, end, pageable);
        return page.map(SimHistoryService::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public ProfitLossSummaryDTO sumProfitLoss(Long userId, String symbol, LocalDateTime start, LocalDateTime end) {
        return HistoryAggregateMapper.toSummary(hasValue(symbol)
                ? simInvestmentHistoryRepository.sumProfitLossByUserIdAndCreatedAtBetweenAndSymbol(userId, start, end, symbol)
                : simInvestmentHistoryRepository.sumProfitLossByUserIdAndCreatedAtBetween(userId, start, end));
    }

    @Override
    @Transactional(readOnly = true)
    public WinLossCountDTO countWinLoss(Long userId, LocalDateTime start, LocalDateTime end) {
        return HistoryAggregateMapper.toWinLoss(simInvestmentHistoryRepository.countWinLoss(userId, start, end));
    }

    @Override
    @Transactional(readOnly = true)
    public WinLossCountDTO countWinLossBySymbol(Long userId, String symbol, LocalDateTime start, LocalDateTime end) {
        return HistoryAggregateMapper.toWinLoss(
                simInvestmentHistoryRepository.countWinLossBySymbol(userId, start, end, symbol));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DailyProfitLossDTO> findDailyProfitLoss(Long userId, String symbol,
                                                        LocalDateTime start, LocalDateTime end) {
        return HistoryAggregateMapper.toDaily(hasValue(symbol)
                ? simInvestmentHistoryRepository.findDailyProfitLossBySymbol(userId, start, end, symbol)
                : simInvestmentHistoryRepository.findDailyProfitLoss(userId, start, end));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SymbolProfitLossDTO> findProfitLossBySymbol(Long userId, String symbol,
                                                            LocalDateTime start, LocalDateTime end) {
        return HistoryAggregateMapper.toSymbol(hasValue(symbol)
                ? simInvestmentHistoryRepository.findProfitLossBySymbolFiltered(userId, start, end, symbol)
                : simInvestmentHistoryRepository.findProfitLossBySymbol(userId, start, end));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InvestmentHistoryDTO> findInvestment(Long id, Long userId) {
        return simInvestmentHistoryRepository.findByIdAndUserId(id, userId).map(SimHistoryService::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TradeHistoryDTO> findTradesUpTo(Long queueStepId, Long userId, LocalDateTime upperBound) {
        return simTradeHistoryRepository
                .findByQueueStepIdAndUserIdAndCreatedAtLessThanEqualOrderByCreatedAtDesc(queueStepId, userId, upperBound)
                .stream().map(SimHistoryService::toDto).toList();
    }

    @Override
    @Transactional
    public void deleteTrades(List<Long> ids, Long userId) {
        simTradeHistoryRepository.deleteAllByIdInAndUserId(ids, userId);
    }

    @Override
    @Transactional
    public void deleteAllTrades(Long userId) {
        simTradeHistoryRepository.deleteAllByUserId(userId);
    }

    @Override
    @Transactional
    public void deleteInvestments(List<Long> ids, Long userId) {
        simInvestmentHistoryRepository.deleteAllByIdInAndUserId(ids, userId);
    }

    @Override
    @Transactional
    public void deleteAllInvestments(Long userId) {
        simInvestmentHistoryRepository.deleteAllByUserId(userId);
    }

    private static boolean hasValue(String s) {
        return s != null && !s.isBlank();
    }

    private static SimTradeHistory toEntity(TradeHistoryDTO d) {
        SimTradeHistory e = new SimTradeHistory();
        e.setQueueStepId(d.getQueueStepId());
        e.setUserId(d.getUserId());
        e.setSymbol(d.getSymbol());
        e.setSide(d.getSide());
        e.setAmount(d.getAmount());
        e.setExecutedPrice(d.getExecutedPrice());
        e.setOrderId(d.getOrderId());
        e.setFee(d.getFee());
        e.setOrderType(d.getOrderType());
        e.setOrderResult(d.getOrderResult());
        e.setErrorMessage(d.getErrorMessage());
        return e;
    }

    private static TradeHistoryDTO toDto(SimTradeHistory e) {
        return TradeHistoryDTO.builder()
                .id(e.getId()).queueStepId(e.getQueueStepId()).userId(e.getUserId())
                .symbol(e.getSymbol()).side(e.getSide()).amount(e.getAmount())
                .executedPrice(e.getExecutedPrice()).orderId(e.getOrderId()).fee(e.getFee())
                .orderType(e.getOrderType()).orderResult(e.getOrderResult())
                .errorMessage(e.getErrorMessage()).createdAt(e.getCreatedAt())
                .build();
    }

    private static SimInvestmentHistory toEntity(InvestmentHistoryDTO d) {
        SimInvestmentHistory e = new SimInvestmentHistory();
        e.setUserId(d.getUserId());
        e.setPatternStepId(d.getPatternStepId());
        e.setSymbol(d.getSymbol());
        e.setSide(d.getSide());
        e.setProfitLoss(d.getProfitLoss());
        e.setEntryPrice(d.getEntryPrice());
        e.setExitPrice(d.getExitPrice());
        e.setAmount(d.getAmount());
        e.setLeverage(d.getLeverage());
        e.setTpPrice(d.getTpPrice());
        e.setSlPrice(d.getSlPrice());
        return e;
    }

    private static InvestmentHistoryDTO toDto(SimInvestmentHistory e) {
        return InvestmentHistoryDTO.builder()
                .id(e.getId()).userId(e.getUserId()).patternStepId(e.getPatternStepId())
                .symbol(e.getSymbol()).side(e.getSide()).profitLoss(e.getProfitLoss())
                .entryPrice(e.getEntryPrice()).exitPrice(e.getExitPrice()).amount(e.getAmount())
                .leverage(e.getLeverage()).tpPrice(e.getTpPrice()).slPrice(e.getSlPrice())
                .createdAt(e.getCreatedAt())
                .build();
    }
}
