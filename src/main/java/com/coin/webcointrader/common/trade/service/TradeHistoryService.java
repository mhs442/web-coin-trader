package com.coin.webcointrader.common.trade.service;

import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.trade.dto.DailyProfitLossDTO;
import com.coin.webcointrader.common.trade.dto.InvestmentHistoryDTO;
import com.coin.webcointrader.common.trade.dto.ProfitLossSummaryDTO;
import com.coin.webcointrader.common.trade.dto.SymbolProfitLossDTO;
import com.coin.webcointrader.common.trade.dto.TradeHistoryDTO;
import com.coin.webcointrader.common.trade.dto.WinLossCountDTO;
import com.coin.webcointrader.common.trade.entity.InvestmentHistory;
import com.coin.webcointrader.common.trade.entity.TradeHistory;
import com.coin.webcointrader.common.trade.repository.InvestmentHistoryRepository;
import com.coin.webcointrader.common.trade.repository.TradeHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * MAIN(실전) 거래/투자 히스토리 서비스.
 * trade_history / investment_history 테이블을 소유하며, 외부에는 DTO만 노출한다.
 */
@Service
@RequiredArgsConstructor
public class TradeHistoryService implements HistoryService {

    private final TradeHistoryRepository tradeHistoryRepository;
    private final InvestmentHistoryRepository investmentHistoryRepository;

    @Override
    @Transactional
    public void saveTrade(TradeHistoryDTO dto) {
        tradeHistoryRepository.save(toEntity(dto));
    }

    @Override
    @Transactional
    public void saveInvestment(InvestmentHistoryDTO dto) {
        investmentHistoryRepository.save(toEntity(dto));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<InvestmentHistoryDTO> findInvestments(Long userId, ExchangeType exchangeType, String symbol,
                                                      LocalDateTime start, LocalDateTime end, Pageable pageable) {
        // 심볼 키워드가 있으면 DB 레벨 부분일치 필터, 없으면 기간만 조회
        Page<InvestmentHistory> page = hasValue(symbol)
                ? investmentHistoryRepository.findByUserIdAndExchangeTypeAndSymbolContainingIgnoreCaseAndCreatedAtBetween(
                        userId, exchangeType, symbol, start, end, pageable)
                : investmentHistoryRepository.findByUserIdAndExchangeTypeAndCreatedAtBetween(userId, exchangeType, start, end, pageable);
        return page.map(TradeHistoryService::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public ProfitLossSummaryDTO sumProfitLoss(Long userId, ExchangeType exchangeType, String symbol, LocalDateTime start, LocalDateTime end) {
        return HistoryAggregateMapper.toSummary(hasValue(symbol)
                ? investmentHistoryRepository.sumProfitLossByUserIdAndCreatedAtBetweenAndSymbol(userId, exchangeType, start, end, symbol)
                : investmentHistoryRepository.sumProfitLossByUserIdAndCreatedAtBetween(userId, exchangeType, start, end));
    }

    @Override
    @Transactional(readOnly = true)
    public WinLossCountDTO countWinLoss(Long userId, ExchangeType exchangeType, LocalDateTime start, LocalDateTime end) {
        return HistoryAggregateMapper.toWinLoss(investmentHistoryRepository.countWinLoss(userId, exchangeType, start, end));
    }

    @Override
    @Transactional(readOnly = true)
    public WinLossCountDTO countWinLossBySymbol(Long userId, ExchangeType exchangeType, String symbol, LocalDateTime start, LocalDateTime end) {
        return HistoryAggregateMapper.toWinLoss(
                investmentHistoryRepository.countWinLossBySymbol(userId, exchangeType, start, end, symbol));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DailyProfitLossDTO> findDailyProfitLoss(Long userId, ExchangeType exchangeType, String symbol,
                                                        LocalDateTime start, LocalDateTime end) {
        return HistoryAggregateMapper.toDaily(hasValue(symbol)
                ? investmentHistoryRepository.findDailyProfitLossBySymbol(userId, exchangeType, start, end, symbol)
                : investmentHistoryRepository.findDailyProfitLoss(userId, exchangeType, start, end));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SymbolProfitLossDTO> findProfitLossBySymbol(Long userId, ExchangeType exchangeType, String symbol,
                                                            LocalDateTime start, LocalDateTime end) {
        return HistoryAggregateMapper.toSymbol(hasValue(symbol)
                ? investmentHistoryRepository.findProfitLossBySymbolFiltered(userId, exchangeType, start, end, symbol)
                : investmentHistoryRepository.findProfitLossBySymbol(userId, exchangeType, start, end));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InvestmentHistoryDTO> findInvestment(Long id, Long userId) {
        return investmentHistoryRepository.findByIdAndUserId(id, userId).map(TradeHistoryService::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TradeHistoryDTO> findTradesUpTo(Long patternId, Long userId, LocalDateTime upperBound) {
        return tradeHistoryRepository
                .findByPatternIdAndUserIdAndCreatedAtLessThanEqualOrderByCreatedAtDesc(patternId, userId, upperBound)
                .stream().map(TradeHistoryService::toDto).toList();
    }

    @Override
    @Transactional
    public void deleteTrades(List<Long> ids, Long userId) {
        tradeHistoryRepository.deleteAllByIdInAndUserId(ids, userId);
    }

    @Override
    @Transactional
    public void deleteAllTrades(Long userId, ExchangeType exchangeType) {
        tradeHistoryRepository.deleteAllByUserIdAndExchangeType(userId, exchangeType);
    }

    @Override
    @Transactional
    public void deleteInvestments(List<Long> ids, Long userId) {
        investmentHistoryRepository.deleteAllByIdInAndUserId(ids, userId);
    }

    @Override
    @Transactional
    public void deleteAllInvestments(Long userId, ExchangeType exchangeType) {
        investmentHistoryRepository.deleteAllByUserIdAndExchangeType(userId, exchangeType);
    }

    private static boolean hasValue(String s) {
        return s != null && !s.isBlank();
    }

    private static TradeHistory toEntity(TradeHistoryDTO d) {
        TradeHistory e = new TradeHistory();
        e.setPatternId(d.getPatternId());
        e.setUserId(d.getUserId());
        e.setSymbol(d.getSymbol());
        // 거래소 기본값 없음 — 미지정이면 exchange_type NOT NULL 로 저장 실패 (임의 거래소로 기록하지 않음)
        e.setExchangeType(d.getExchangeType());
        e.setSide(d.getSide());
        e.setAmount(d.getAmount());
        e.setExecutedPrice(d.getExecutedPrice());
        e.setOrderId(d.getOrderId());
        e.setFee(d.getFee());
        e.setOrderType(d.getOrderType());
        e.setOrderResult(d.getOrderResult());
        e.setErrorMessage(HistoryService.truncateErrorMessage(d.getErrorMessage())); // VARCHAR(500) 초과분 절단
        return e;
    }

    private static TradeHistoryDTO toDto(TradeHistory e) {
        return TradeHistoryDTO.builder()
                .id(e.getId()).patternId(e.getPatternId()).userId(e.getUserId())
                .symbol(e.getSymbol()).exchangeType(e.getExchangeType()).side(e.getSide()).amount(e.getAmount())
                .executedPrice(e.getExecutedPrice()).orderId(e.getOrderId()).fee(e.getFee())
                .orderType(e.getOrderType()).orderResult(e.getOrderResult())
                .errorMessage(e.getErrorMessage()).createdAt(e.getCreatedAt())
                .build();
    }

    private static InvestmentHistory toEntity(InvestmentHistoryDTO d) {
        InvestmentHistory e = new InvestmentHistory();
        e.setUserId(d.getUserId());
        e.setPatternId(d.getPatternId());
        e.setSymbol(d.getSymbol());
        // 거래소 기본값 없음 — 미지정이면 exchange_type NOT NULL 로 저장 실패 (임의 거래소로 기록하지 않음)
        e.setExchangeType(d.getExchangeType());
        e.setSide(d.getSide());
        e.setProfitLoss(d.getProfitLoss());
        e.setEntryPrice(d.getEntryPrice());
        e.setExitPrice(d.getExitPrice());
        e.setAmount(d.getAmount());
        e.setLeverage(d.getLeverage());
        e.setTpPrice(d.getTpPrice());
        e.setSlPrice(d.getSlPrice());
        e.setFundingFee(d.getFundingFee());
        return e;
    }

    private static InvestmentHistoryDTO toDto(InvestmentHistory e) {
        return InvestmentHistoryDTO.builder()
                .id(e.getId()).userId(e.getUserId()).patternId(e.getPatternId())
                .symbol(e.getSymbol()).exchangeType(e.getExchangeType()).side(e.getSide()).profitLoss(e.getProfitLoss())
                .entryPrice(e.getEntryPrice()).exitPrice(e.getExitPrice()).amount(e.getAmount())
                .leverage(e.getLeverage()).tpPrice(e.getTpPrice()).slPrice(e.getSlPrice())
                .fundingFee(e.getFundingFee()).createdAt(e.getCreatedAt())
                .build();
    }
}
