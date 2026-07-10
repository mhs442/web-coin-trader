package com.coin.webcointrader.trade.service;

import com.coin.webcointrader.common.dto.request.CreateOrderRequest;
import com.coin.webcointrader.common.dto.request.SetLeverageRequest;
import com.coin.webcointrader.common.dto.response.CreateOrderResponse;
import com.coin.webcointrader.common.dto.response.GetClosedPnlResponse;
import com.coin.webcointrader.common.dto.response.GetExecutionListResponse;
import com.coin.webcointrader.common.dto.response.GetWalletBalanceResponse;
import com.coin.webcointrader.common.dto.response.SetLeverageResponse;
import com.coin.webcointrader.common.dto.response.SetMarginModeResponse;
import com.coin.webcointrader.common.entity.TradeHistory;
import com.coin.webcointrader.common.enums.TradeMode;
import com.coin.webcointrader.sim.service.SimTradeService;
import com.coin.webcointrader.sim.service.SimWalletService;
import com.coin.webcointrader.wallet.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 거래 Facade 서비스.
 * 거래 모드(MAIN/SIM)에 따라 실전 서비스 또는 모의투자 서비스로 위임하는 단일 진입점이다.
 *
 * @see TradeService 실전 거래 서비스
 * @see SimTradeService 모의투자 거래 서비스
 */
@Service
@RequiredArgsConstructor
public class TradeFacade {

    private final TradeService tradeService;
    private final SimTradeService simTradeService;
    private final WalletService walletService;
    private final SimWalletService simWalletService;

    /**
     * 주문을 실행한다.
     *
     * @param request   주문 요청
     * @param userId    사용자 ID
     * @param history   거래 이력 (자동매매 시 전달, null 가능)
     * @param tradeMode 거래 모드 (MAIN: 실전, SIM: 모의)
     * @return 주문 응답
     */
    public CreateOrderResponse placeOrder(CreateOrderRequest request, Long userId,
                                          TradeHistory history, TradeMode tradeMode) {
        // 모의투자 모드
        if (tradeMode == TradeMode.SIM) {
            return simTradeService.placeOrder(request, userId, history);
        }
        // 실전 모드
        return tradeService.placeOrder(request, userId, history);
    }

    /**
     * 주문을 실행한다. (수동 주문용)
     *
     * @param request   주문 요청
     * @param userId    사용자 ID
     * @param tradeMode 거래 모드 (MAIN: 실전, SIM: 모의)
     * @return 주문 응답
     */
    public CreateOrderResponse placeOrder(CreateOrderRequest request, Long userId, TradeMode tradeMode) {
        return placeOrder(request, userId, null, tradeMode);
    }

    /**
     * 마진 모드를 Isolated로 전환한다.
     *
     * @param userId    사용자 ID
     * @param tradeMode 거래 모드
     * @return 마진 모드 설정 응답
     */
    public SetMarginModeResponse switchToIsolated(Long userId, TradeMode tradeMode) {
        if (tradeMode == TradeMode.SIM) {
            return simTradeService.switchToIsolated(userId);
        }
        return tradeService.switchToIsolated(userId);
    }

    /**
     * 레버리지를 설정한다.
     *
     * @param request   레버리지 설정 요청
     * @param userId    사용자 ID
     * @param tradeMode 거래 모드
     * @return 레버리지 설정 응답
     */
    public SetLeverageResponse setLeverage(SetLeverageRequest request, Long userId, TradeMode tradeMode) {
        if (tradeMode == TradeMode.SIM) {
            return simTradeService.setLeverage(request, userId);
        }
        return tradeService.setLeverage(request, userId);
    }

    /**
     * 지갑 잔고를 조회한다.
     *
     * @param userId    사용자 ID
     * @param tradeMode 거래 모드
     * @return 지갑 잔고 응답
     */
    public GetWalletBalanceResponse getBalance(Long userId, TradeMode tradeMode) {
        if (tradeMode == TradeMode.SIM) {
            return simWalletService.getWalletBalance(userId);
        }
        return walletService.getWalletBalance(userId);
    }

    /**
     * MAIN 모드에서 주문의 실체결 정보를 Bybit에서 조회한다.
     * SIM 모드는 실체결이 없으므로 null을 반환한다.
     *
     * @param orderId   Bybit 주문 ID
     * @param symbol    심볼
     * @param userId    사용자 ID
     * @param tradeMode 거래 모드
     * @return 실체결 정보, SIM 모드이거나 조회 실패 시 null
     */
    public GetExecutionListResponse.ExecutionInfo getExecution(
            String orderId, String symbol, Long userId, TradeMode tradeMode) {
        if (tradeMode == TradeMode.SIM) return null;
        return tradeService.getExecution(orderId, symbol, userId);
    }

    /**
     * MAIN 모드에서 가장 최근 청산된 포지션의 손익 정보를 Bybit에서 조회한다. (closedPnl + fundingFee 포함)
     * SIM 모드는 실체결이 없으므로 null을 반환한다.
     *
     * @param symbol    심볼
     * @param userId    사용자 ID
     * @param tradeMode 거래 모드
     * @return 청산 손익 정보, SIM 모드이거나 조회 실패 시 null
     */
    public GetClosedPnlResponse.ClosedPnlInfo getClosedPnl(String symbol, Long userId, TradeMode tradeMode) {
        if (tradeMode == TradeMode.SIM) return null;
        return tradeService.getClosedPnl(symbol, userId);
    }

    /**
     * 모의투자 매도/청산 시 손익을 가상 지갑에 반영한다.
     * 실전 모드에서는 아무 동작도 하지 않는다. (실제 Bybit에서 자동 반영)
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
        // 실전 모드: Bybit에서 자동 반영되므로 no-op
    }
}
