package com.coin.webcointrader.sim.service;

import com.coin.webcointrader.common.dto.request.CreateOrderRequest;
import com.coin.webcointrader.common.dto.request.SetLeverageRequest;
import com.coin.webcointrader.common.dto.response.CreateOrderResponse;
import com.coin.webcointrader.common.dto.response.SetLeverageResponse;
import com.coin.webcointrader.common.dto.response.SetMarginModeResponse;
import com.coin.webcointrader.common.entity.SimTradeHistory;
import com.coin.webcointrader.common.entity.TradeHistory;
import com.coin.webcointrader.common.enums.OrderResult;
import com.coin.webcointrader.sim.repository.SimTradeHistoryRepository;
import com.coin.webcointrader.sim.repository.SimWalletRepository;
import com.coin.webcointrader.common.entity.SimWallet;
import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.exception.CustomException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 모의투자 주문 실행 서비스.
 * 실제 Bybit API를 호출하지 않고, 현재가 기반으로 가상 체결을 시뮬레이션한다.
 * 주문 시 SimWallet 잔고를 차감하고, 매도/청산 시 손익을 반영한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SimTradeService {

    private final SimTradeHistoryRepository simTradeHistoryRepository;
    private final SimWalletRepository simWalletRepository;

    /**
     * 모의 주문을 실행한다. (수동 주문용 - TradeHistory 없음)
     *
     * @param request 주문 요청
     * @param userId  사용자 ID
     * @return 가상 주문 응답 (retCode "0", orderId "SIM-xxx")
     */
    public CreateOrderResponse placeOrder(CreateOrderRequest request, Long userId) {
        return placeOrder(request, userId, null);
    }

    /**
     * 모의 주문을 실행한다.
     * 실제 API 호출 없이 현재가로 즉시 체결된 것으로 처리한다.
     * 주문 금액만큼 SimWallet 잔고를 차감한다.
     *
     * @param request 주문 요청 (symbol, side, qty 등)
     * @param userId  사용자 ID
     * @param history 거래 이력 엔티티 (자동매매 시 전달, null 가능)
     * @return 가상 주문 응답
     */
    @Transactional
    public CreateOrderResponse placeOrder(CreateOrderRequest request, Long userId, TradeHistory history) {
        // 가상 지갑 조회
        SimWallet wallet = simWalletRepository.findByUserId(userId)
                .orElseThrow(() -> new CustomException(ExceptionMessage.USER_NOT_FOUND));

        // 잔고 차감 (진입 주문 시)
        if (history != null && history.getAmount() != null) {
            BigDecimal orderAmount = history.getAmount();

            // 잔고 부족 시 실패 처리
            if (wallet.getBalance().compareTo(orderAmount) < 0) {
                if (history != null) {
                    // SimTradeHistory로 변환하여 실패 기록 저장
                    SimTradeHistory simHistory = toSimTradeHistory(history);
                    simHistory.setExecutedPrice(BigDecimal.ZERO);
                    simHistory.setOrderResult(OrderResult.FAILED);
                    simHistory.setErrorMessage("모의투자 잔고 부족");
                    simTradeHistoryRepository.save(simHistory);
                }
                throw new CustomException(ExceptionMessage.ORDER_FAILED, "모의투자 잔고 부족");
            }

            wallet.setBalance(wallet.getBalance().subtract(orderAmount));
            simWalletRepository.save(wallet);
        }

        // 가상 주문 응답 생성 (성공)
        CreateOrderResponse response = new CreateOrderResponse();
        response.setRetCode("0");
        response.setRetMsg("OK");

        CreateOrderResponse.Result result = new CreateOrderResponse.Result();
        result.setOrderId("SIM-" + System.currentTimeMillis());
        result.setOrderLinkId("");
        response.setResult(result);

        log.info("모의투자 주문 체결: userId={}, symbol={}, side={}, qty={}",
                userId, request.getSymbol(), request.getSide(), request.getQty());

        return response;
    }

    /**
     * 모의투자 마진 모드 전환 (no-op, 성공 응답 반환)
     *
     * @param userId 사용자 ID
     * @return 성공 응답
     */
    public SetMarginModeResponse switchToIsolated(Long userId) {
        SetMarginModeResponse response = new SetMarginModeResponse();
        response.setRetCode("0");
        response.setRetMsg("OK");
        return response;
    }

    /**
     * 모의투자 레버리지 설정 (no-op, 성공 응답 반환)
     *
     * @param request 레버리지 설정 요청
     * @param userId  사용자 ID
     * @return 성공 응답
     */
    public SetLeverageResponse setLeverage(SetLeverageRequest request, Long userId) {
        SetLeverageResponse response = new SetLeverageResponse();
        response.setRetCode("0");
        response.setRetMsg("OK");
        return response;
    }

    /**
     * 모의투자 매도/청산 시 손익을 가상 지갑에 반영한다.
     *
     * @param userId     사용자 ID
     * @param amount     원금 (USDT)
     * @param profitLoss 손익금 (양수: 이익, 음수: 손실)
     */
    @Transactional
    public void applyProfitLoss(Long userId, BigDecimal amount, BigDecimal profitLoss) {
        SimWallet wallet = simWalletRepository.findByUserId(userId)
                .orElseThrow(() -> new CustomException(ExceptionMessage.USER_NOT_FOUND));

        // 원금 + 손익금 반환
        BigDecimal returned = amount.add(profitLoss);
        wallet.setBalance(wallet.getBalance().add(returned));
        simWalletRepository.save(wallet);

        log.info("모의투자 손익 반영: userId={}, amount={}, profitLoss={}, newBalance={}",
                userId, amount, profitLoss, wallet.getBalance());
    }

    /**
     * TradeHistory → SimTradeHistory 변환.
     * 부모 필드를 복사하여 SimTradeHistory 객체를 생성한다.
     *
     * @param history 원본 TradeHistory
     * @return SimTradeHistory 객체
     */
    private SimTradeHistory toSimTradeHistory(TradeHistory history) {
        SimTradeHistory sim = new SimTradeHistory();
        sim.setQueueStepId(history.getQueueStepId());
        sim.setUserId(history.getUserId());
        sim.setSymbol(history.getSymbol());
        sim.setSide(history.getSide());
        sim.setAmount(history.getAmount());
        sim.setExecutedPrice(history.getExecutedPrice());
        sim.setOrderType(history.getOrderType());
        sim.setOrderResult(history.getOrderResult());
        sim.setErrorMessage(history.getErrorMessage());
        return sim;
    }
}
