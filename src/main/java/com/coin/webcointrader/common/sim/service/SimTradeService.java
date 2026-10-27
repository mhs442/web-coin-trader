package com.coin.webcointrader.common.sim.service;

import com.coin.webcointrader.common.trade.dto.OrderRequestDTO;
import com.coin.webcointrader.common.trade.dto.OrderResultDTO;
import com.coin.webcointrader.common.trade.dto.TradeHistoryDTO;
import com.coin.webcointrader.common.enums.LogMessage;
import com.coin.webcointrader.common.enums.OrderResult;
import com.coin.webcointrader.common.enums.TradeOrderType;
import com.coin.webcointrader.common.sim.repository.SimWalletRepository;
import com.coin.webcointrader.common.sim.entity.SimWallet;
import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.exception.CustomException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 모의투자 주문 실행 서비스.
 * 실제 거래소 API를 호출하지 않고, 현재가 기반으로 가상 체결을 시뮬레이션한다.
 * 주문 시 SimWallet 잔고를 차감하고, 매도/청산 시 손익을 반영한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SimTradeService {

    private final SimHistoryService simHistoryService;
    private final SimWalletRepository simWalletRepository;

    /**
     * 모의 주문을 실행한다.
     * 실제 API 호출 없이 현재가로 즉시 체결된 것으로 처리한다.
     * 주문 금액만큼 SimWallet 잔고를 차감한다.
     *
     * @param request 주문 요청 (symbol, side, qty 등)
     * @param userId  사용자 ID
     * @param history 거래 이력 DTO (자동매매 시 전달, null 가능)
     * @return 가상 주문 결과 (orderId "SIM-{epoch ms}", clientOrderId는 요청 값 그대로)
     * @throws CustomException 진입 주문 잔고 부족 시 (ORDER_FAILED, 실패 이력 저장 후)
     */
    @Transactional
    public OrderResultDTO placeOrder(OrderRequestDTO request, Long userId, TradeHistoryDTO history) {
        // 가상 지갑 조회
        SimWallet wallet = simWalletRepository.findByUserId(userId)
                .orElseThrow(() -> new CustomException(ExceptionMessage.USER_NOT_FOUND));

        // 잔고 차감 (진입 주문 시에만 차감 — 매도/청산은 applyProfitLoss에서 손익 반영)
        if (history != null && history.getAmount() != null
                && TradeOrderType.ENTRY.getTradeOrderType().equals(history.getOrderType())) {
            // 마진 + 진입 수수료 합산 차감
            BigDecimal fee = history.getFee() != null ? history.getFee() : BigDecimal.ZERO;
            BigDecimal orderAmount = history.getAmount().add(fee);

            // 잔고 부족 시 실패 처리
            if (wallet.getBalance().compareTo(orderAmount) < 0) {
                // 실패 기록 저장 (SIM 히스토리 테이블)
                history.setExecutedPrice(BigDecimal.ZERO);
                history.setOrderResult(OrderResult.FAILED);
                history.setErrorMessage("모의투자 잔고 부족");
                simHistoryService.saveTrade(history);
                throw new CustomException(ExceptionMessage.ORDER_FAILED, "모의투자 잔고 부족");
            }

            wallet.setBalance(wallet.getBalance().subtract(orderAmount));
            simWalletRepository.save(wallet);
        }

        log.info(LogMessage.SIM_ORDER_EXECUTED.getMessage(),
                userId, request.getSymbol(), request.getSide(), request.getQty());

        // 가상 주문 결과 생성 (성공)
        return OrderResultDTO.builder()
                .orderId("SIM-" + System.currentTimeMillis())
                .clientOrderId(request.getClientOrderId())
                .build();
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

        log.info(LogMessage.SIM_PROFIT_LOSS_APPLIED.getMessage(),
                userId, amount, profitLoss, wallet.getBalance());
    }
}
