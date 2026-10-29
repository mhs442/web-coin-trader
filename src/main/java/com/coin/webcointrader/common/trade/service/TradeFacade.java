package com.coin.webcointrader.common.trade.service;

import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.enums.OrderCancelResult;
import com.coin.webcointrader.common.enums.OrderResult;
import com.coin.webcointrader.common.enums.TradeMode;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.ExchangeClientRegistry;
import com.coin.webcointrader.common.trade.dto.PositionCloseDTO;
import com.coin.webcointrader.common.trade.dto.ClosedPnlDTO;
import com.coin.webcointrader.common.trade.dto.ClosedPnlQueryDTO;
import com.coin.webcointrader.common.trade.dto.ExecutionDTO;
import com.coin.webcointrader.common.trade.dto.OrderRequestDTO;
import com.coin.webcointrader.common.trade.dto.OrderResultDTO;
import com.coin.webcointrader.common.account.dto.WalletBalanceDTO;
import com.coin.webcointrader.common.trade.dto.InvestmentHistoryDTO;
import com.coin.webcointrader.common.trade.dto.TradeHistoryDTO;
import com.coin.webcointrader.common.sim.service.SimHistoryService;
import com.coin.webcointrader.common.sim.service.SimTradeService;
import com.coin.webcointrader.common.sim.service.SimWalletService;
import com.coin.webcointrader.common.wallet.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 거래 Facade 서비스.
 * 거래 모드(MAIN/SIM)에 따라 거래소 거래 클라이언트 또는 모의투자 서비스로 위임하는 단일 진입점이다.
 * MAIN은 거래소 타입으로 {@link com.coin.webcointrader.common.trade.client.ExchangeTradeClient}를 골라 위임한다.
 *
 * @see SimTradeService 모의투자 거래 서비스
 */
@Service
@RequiredArgsConstructor
public class TradeFacade {

    private final ExchangeClientRegistry registry;
    private final SimTradeService simTradeService;
    private final WalletService walletService;
    private final SimWalletService simWalletService;
    private final TradeHistoryService tradeHistoryService;
    private final SimHistoryService simHistoryService;

    /**
     * 주문을 실행한다.
     * MAIN 주문이 거래소에서 거부(ORDER_FAILED)되면 실패 이력을 저장한 뒤 예외를 다시 던진다.
     * (통신 오류·키 없음 등 그 외 예외는 이력 없이 그대로 전파)
     *
     * @param request      주문 요청
     * @param userId       사용자 ID
     * @param history      거래 이력 (자동매매 시 전달, null 가능)
     * @param exchangeType 거래소 타입
     * @param tradeMode    거래 모드 (MAIN: 실전, SIM: 모의)
     * @return 주문 결과 (거래소 주문 ID)
     * @throws CustomException 주문 거부 (ORDER_FAILED), 키 없음 (API_KEY_NOT_FOUND) 등
     */
    public OrderResultDTO placeOrder(OrderRequestDTO request, Long userId, TradeHistoryDTO history,
                                     ExchangeType exchangeType, TradeMode tradeMode) {
        // 모의투자 모드
        if (tradeMode == TradeMode.SIM) {
            return simTradeService.placeOrder(request, userId, history);
        }
        // 실전 모드
        try {
            return registry.trade(exchangeType).placeOrder(request, userId);
        } catch (CustomException e) {
            // 거래소 거부 확정 → 실패 이력 저장 (자동매매 호출 시에만 history 존재)
            if (e.getExceptionMessage() == ExceptionMessage.ORDER_FAILED) saveFailedHistory(history, e);
            throw e;
        }
    }

    /**
     * 주문을 실행한다. (수동 주문용 - 거래 이력 없음)
     *
     * @param request      주문 요청
     * @param userId       사용자 ID
     * @param exchangeType 거래소 타입
     * @param tradeMode    거래 모드 (MAIN: 실전, SIM: 모의)
     * @return 주문 결과
     */
    public OrderResultDTO placeOrder(OrderRequestDTO request, Long userId, ExchangeType exchangeType, TradeMode tradeMode) {
        return placeOrder(request, userId, null, exchangeType, tradeMode);
    }

    /**
     * 마진 모드를 Isolated로 전환한다. SIM 모드는 아무것도 하지 않는다.
     * 거래소가 거부(SWITCH_MARGIN_MODE_FAILED)하면 실패 이력을 저장한 뒤 예외를 다시 던진다.
     *
     * @param userId       사용자 ID
     * @param symbol       종목 심볼
     * @param history      진입 거래 이력 (실패 기록용, null 가능)
     * @param exchangeType 거래소 타입
     * @param tradeMode    거래 모드
     * @throws CustomException 전환 실패 시 (SWITCH_MARGIN_MODE_FAILED)
     */
    public void switchToIsolated(Long userId, String symbol, TradeHistoryDTO history,
                                 ExchangeType exchangeType, TradeMode tradeMode) {
        if (tradeMode == TradeMode.SIM) return;
        try {
            registry.trade(exchangeType).switchToIsolated(userId, symbol);
        } catch (CustomException e) {
            // [사용자 정책 2026-10-27] 진입 전 설정 거부도 거래 히스토리에 FAILED로 기록
            if (e.getExceptionMessage() == ExceptionMessage.SWITCH_MARGIN_MODE_FAILED) saveFailedHistory(history, e);
            throw e;
        }
    }

    /**
     * 심볼 레버리지를 설정한다. (롱·숏 동일) SIM 모드는 아무것도 하지 않는다.
     *
     * @param userId       사용자 ID
     * @param symbol       심볼 (예: "BTCUSDT")
     * @param leverage     레버리지 (예: "10")
     * @param history      진입 거래 이력 (실패 기록용, null 가능)
     * @param exchangeType 거래소 타입
     * @param tradeMode    거래 모드
     * @throws CustomException 설정 실패 시 (SET_LEVERAGE_FAILED)
     */
    public void setLeverage(Long userId, String symbol, String leverage, TradeHistoryDTO history,
                            ExchangeType exchangeType, TradeMode tradeMode) {
        if (tradeMode == TradeMode.SIM) return;
        try {
            registry.trade(exchangeType).setLeverage(userId, symbol, leverage);
        } catch (CustomException e) {
            // [사용자 정책 2026-10-27] 진입 전 설정 거부도 거래 히스토리에 FAILED로 기록
            if (e.getExceptionMessage() == ExceptionMessage.SET_LEVERAGE_FAILED) saveFailedHistory(history, e);
            throw e;
        }
    }

    /**
     * 거래소 거부 사유를 담아 실패 이력을 저장한다. (history가 없으면 저장하지 않음)
     *
     * @param history 거래 이력 (null 가능)
     * @param e       거래소 거부 예외 (메시지 = "구분: [코드] 거래소 메시지")
     */
    private void saveFailedHistory(TradeHistoryDTO history, CustomException e) {
        if (history == null) return;
        history.setExecutedPrice(BigDecimal.ZERO);
        history.setOrderResult(OrderResult.FAILED);
        history.setErrorMessage(e.getMessage());
        tradeHistoryService.saveTrade(history);
    }

    /**
     * 지갑 잔고를 조회한다.
     *
     * @param userId       사용자 ID
     * @param exchangeType 거래소 타입 (MAIN에서만 사용)
     * @param tradeMode    거래 모드
     * @return 지갑 잔고 (MAIN: 거래소 실전 지갑, SIM: 가상 지갑)
     */
    public WalletBalanceDTO getBalance(Long userId, ExchangeType exchangeType, TradeMode tradeMode) {
        if (tradeMode == TradeMode.SIM) {
            return simWalletService.getWalletBalance(userId);
        }
        return walletService.getWalletBalance(userId, exchangeType);
    }

    /**
     * MAIN 모드에서 주문의 체결 대기를 등록한다. 주문 전송 전에 호출해야 한다.
     * SIM 모드는 실체결이 없으므로 아무것도 하지 않는다.
     *
     * @param userId        사용자 ID
     * @param clientOrderId 주문에 지정할 사용자 지정 주문 ID
     * @param exchangeType  거래소 타입
     * @param tradeMode     거래 모드
     */
    public void expectExecution(Long userId, String clientOrderId, ExchangeType exchangeType, TradeMode tradeMode) {
        if (tradeMode == TradeMode.SIM) return;
        registry.trade(exchangeType).expectExecution(userId, clientOrderId);
    }

    /**
     * MAIN 모드에서 주문의 체결 대기를 취소한다. SIM 모드는 아무것도 하지 않는다.
     *
     * @param clientOrderId 취소할 사용자 지정 주문 ID
     * @param exchangeType  거래소 타입
     * @param tradeMode     거래 모드
     */
    public void cancelExecution(String clientOrderId, ExchangeType exchangeType, TradeMode tradeMode) {
        if (tradeMode == TradeMode.SIM) return;
        registry.trade(exchangeType).cancelExecution(clientOrderId);
    }

    /**
     * MAIN 모드에서 미체결 주문을 clientOrderId로 취소한다. (익절 지정가 취소용)
     * SIM 모드는 거래소에 걸린 주문이 없으므로 CANCELLED를 반환한다.
     *
     * @param userId        사용자 ID
     * @param symbol        심볼
     * @param clientOrderId 취소할 주문의 사용자 지정 ID
     * @param exchangeType  거래소 타입
     * @param tradeMode     거래 모드
     * @return CANCELLED: 취소 성공(SIM 포함) / NOT_FOUND: 이미 전부 체결·취소 / FAILED: 그 외 실패
     */
    public OrderCancelResult cancelOrder(Long userId, String symbol, String clientOrderId,
                                         ExchangeType exchangeType, TradeMode tradeMode) {
        if (tradeMode == TradeMode.SIM) return OrderCancelResult.CANCELLED;
        return registry.trade(exchangeType).cancelOrder(userId, symbol, clientOrderId);
    }

    /**
     * MAIN 모드에서 주문의 실체결 정보를 스트림 우선으로 얻는다. (미수신 시 REST 조회)
     * SIM 모드는 실체결이 없으므로 null을 반환한다.
     *
     * @param orderId       거래소 주문 ID
     * @param clientOrderId 사용자 지정 주문 ID
     * @param symbol        심볼
     * @param userId        사용자 ID
     * @param exchangeType  거래소 타입
     * @param tradeMode     거래 모드
     * @return 집계된 실체결 정보, SIM 모드이거나 조회 실패 시 null
     */
    public ExecutionDTO getExecution(String orderId, String clientOrderId, String symbol, Long userId,
                                     ExchangeType exchangeType, TradeMode tradeMode) {
        if (tradeMode == TradeMode.SIM) return null;
        return registry.trade(exchangeType).getExecution(orderId, clientOrderId, symbol, userId);
    }

    /**
     * MAIN 모드에서 청산 주문 ID에 해당하는 실현 손익을 조회한다. (closedPnl + fundingFee 포함)
     * SIM 모드는 실체결이 없으므로 null을 반환한다.
     *
     * @param query     조회 조건 (exchangeType 으로 조회할 거래소를 정함)
     * @param userId    사용자 ID
     * @param tradeMode 거래 모드
     * @return 청산 손익, SIM 모드이거나 조회 실패 시 null
     */
    public ClosedPnlDTO getClosedPnl(ClosedPnlQueryDTO query, Long userId, TradeMode tradeMode) {
        if (tradeMode == TradeMode.SIM) return null;
        return registry.trade(query.getExchangeType()).getClosedPnl(query, userId);
    }

    /**
     * MAIN 모드에서 심볼의 현재 포지션 수량을 조회한다. (외부 청산 확인용)
     * SIM 모드는 거래소 포지션이 없으므로 null을 반환한다.
     *
     * @param userId       사용자 ID
     * @param symbol       심볼
     * @param exchangeType 거래소 타입
     * @param tradeMode    거래 모드
     * @return 포지션 수량 (포지션 없으면 0), SIM 모드이거나 조회 실패 시 null
     */
    public BigDecimal getPositionSize(Long userId, String symbol, ExchangeType exchangeType, TradeMode tradeMode) {
        if (tradeMode == TradeMode.SIM) return null;
        return registry.trade(exchangeType).getPositionSize(userId, symbol);
    }

    /**
     * MAIN 모드에서 기준 시각 이후 심볼의 가장 최근 청산 건과 청산 방식을 조회한다. (놓친 외부 청산 재조정용)
     * SIM 모드는 거래소 청산 내역이 없으므로 null을 반환한다.
     *
     * @param userId       사용자 ID
     * @param symbol       심볼
     * @param sinceMillis  검색 시작 시각 (epoch ms, 보통 진입 시각)
     * @param exchangeType 거래소 타입
     * @param tradeMode    거래 모드
     * @return 청산 이벤트 (청산 건 없으면 orderId null·OTHER), SIM 모드이거나 조회 실패 시 null
     */
    public PositionCloseDTO findLastClose(Long userId, String symbol, long sinceMillis,
                                          ExchangeType exchangeType, TradeMode tradeMode) {
        if (tradeMode == TradeMode.SIM) return null;
        return registry.trade(exchangeType).findLastClose(userId, symbol, sinceMillis);
    }

    /**
     * 모의투자 매도/청산 시 손익을 가상 지갑에 반영한다.
     * 실전 모드에서는 아무 동작도 하지 않는다. (거래소에서 자동 반영)
     *
     * @param userId     사용자 ID
     * @param amount     원금 (USDT)
     * @param profitLoss 손익금
     * @param tradeMode  거래 모드
     */
    public void applyProfitLoss(Long userId, BigDecimal amount, BigDecimal profitLoss, TradeMode tradeMode) {
        if (tradeMode == TradeMode.SIM) {
            simTradeService.applyProfitLoss(userId, amount, profitLoss);
        }
        // 실전 모드: 거래소에서 자동 반영되므로 no-op
    }

    /**
     * 거래 히스토리를 모드에 맞는 테이블에 저장한다.
     *
     * @param dto       저장할 거래 히스토리
     * @param tradeMode 거래 모드 (MAIN: trade_history, SIM: sim_trade_history)
     */
    public void saveTradeHistory(TradeHistoryDTO dto, TradeMode tradeMode) {
        historyService(tradeMode).saveTrade(dto);
    }

    /**
     * 투자 히스토리를 모드에 맞는 테이블에 저장한다.
     *
     * @param dto       저장할 투자 히스토리
     * @param tradeMode 거래 모드 (MAIN: investment_history, SIM: sim_investment_history)
     */
    public void saveInvestmentHistory(InvestmentHistoryDTO dto, TradeMode tradeMode) {
        historyService(tradeMode).saveInvestment(dto);
    }

    /**
     * 거래 모드에 해당하는 히스토리 서비스를 반환한다.
     *
     * @param tradeMode 거래 모드
     * @return SIM이면 {@link SimHistoryService}, 그 외는 {@link TradeHistoryService}
     */
    public HistoryService historyService(TradeMode tradeMode) {
        return tradeMode == TradeMode.SIM ? simHistoryService : tradeHistoryService;
    }
}
