package com.coin.webcointrader.trade.service;

import com.coin.webcointrader.common.client.position.PositionClient;
import com.coin.webcointrader.common.client.trade.TradeClient;
import com.coin.webcointrader.common.dto.request.CreateOrderRequest;
import com.coin.webcointrader.common.dto.request.SetLeverageRequest;
import com.coin.webcointrader.common.dto.request.SetTradingStopRequest;
import com.coin.webcointrader.common.dto.response.CreateOrderResponse;
import com.coin.webcointrader.common.dto.response.SetLeverageResponse;
import com.coin.webcointrader.common.dto.response.SetTradingStopResponse;
import com.coin.webcointrader.common.entity.User;
import com.coin.webcointrader.common.enums.Category;
import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.util.AesEncryptor;
import com.coin.webcointrader.common.util.UserApiKeyContext;
import com.coin.webcointrader.login.repository.LoginRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Bybit 주문 실행 서비스.
 * 사용자의 API Key/Secret을 복호화하여 주문을 전송하고, 요청 컨텍스트를 안전하게 정리한다.
 */
@Service
@RequiredArgsConstructor
public class TradeService {

    private final TradeClient tradeClient;
    private final PositionClient positionClient;
    private final LoginRepository loginRepository;
    private final AesEncryptor aesEncryptor;

    /**
     * Bybit에 주문을 실행한다.
     * category가 없으면 LINEAR로 기본 설정하고,
     * 사용자 API Key/Secret을 복호화하여 {@link com.coin.webcointrader.common.util.UserApiKeyContext}에 설정 후 주문을 전송한다.
     * Bybit 응답의 retCode가 "0"이 아니면 TradeHistory에 실패 이력을 저장한 후 예외를 던진다.
     *
     * @param request 주문 요청 (symbol, side, orderType, qty 등 포함, category 생략 가능)
     * @param userId  API Key를 조회할 사용자 ID
     * @param history 거래 이력 엔티티 (실패 시 저장용, null 가능)
     * @return 주문 생성 응답 (Bybit 주문 ID 포함)
     * @throws CustomException 주문 실패 시 (ORDER_FAILED), 사용자를 찾을 수 없는 경우 (USER_NOT_FOUND)
     */
    public CreateOrderResponse placeOrder(CreateOrderRequest request, Long userId, TradeHistory history) {
        // category가 없으면 LINEAR로 기본 설정
        if (request.getCategory() == null) {
            request = CreateOrderRequest.builder()
                    .category(Category.LINEAR.getCategory())
                    .symbol(request.getSymbol())
                    .side(request.getSide())
                    .orderType(request.getOrderType())
                    .qty(request.getQty())
                    .price(request.getPrice())
                    .build();
        }

        setApiKeyContext(userId);
        try {
            CreateOrderResponse response = tradeClient.createOrder(request).getBody();

            // Bybit 응답 에러 코드 검증
            if (response == null || !"0".equals(response.getRetCode())) {
                String errorMsg = response != null ? response.getRetMsg() : "응답 없음";
                // 실패 이력 저장 후 예외 (자동매매 호출 시에만 history 존재)
                if (history != null) {
                    history.setExecutedPrice(BigDecimal.ZERO);
                    history.setOrderResult(OrderResult.FAILED);
                    history.setErrorMessage(errorMsg);
                    tradeHistoryRepository.save(history);
                }
                throw new CustomException(ExceptionMessage.ORDER_FAILED, errorMsg);
            }

            return response;
        } finally {
            UserApiKeyContext.clear();
        }
    }

    /**
     * UTA 계정의 마진 모드를 Isolated로 전환한다.
     * 이미 Isolated 모드인 경우(retCode "110026")는 정상 처리한다.
     *
     * @param userId API Key를 조회할 사용자 ID
     * @return 마진 모드 설정 결과 응답
     * @throws CustomException 마진 모드 변경 실패 시 (SWITCH_MARGIN_MODE_FAILED)
     */
    public SetMarginModeResponse switchToIsolated(Long userId) {
        setApiKeyContext(userId);
        try {
            SetMarginModeRequest request = new SetMarginModeRequest("ISOLATED_MARGIN");
            SetMarginModeResponse response = positionClient.setMarginMode(request).getBody();

            // Bybit 응답 에러 코드 검증 (110026: 이미 동일 마진 모드 설정됨 → 무시)
            if (response == null || (!"0".equals(response.getRetCode()) && !"110026".equals(response.getRetCode()))) {
                String errorMsg = response != null ? response.getRetMsg() : "응답 없음";
                throw new CustomException(ExceptionMessage.SWITCH_MARGIN_MODE_FAILED, errorMsg);
            }

            return response;
        } finally {
            UserApiKeyContext.clear();
        }
    }

    /**
     * Bybit에 레버리지를 설정한다.
     * 사용자 API Key 컨텍스트를 설정한 후 레버리지 변경 API를 호출한다.
     *
     * @param request 레버리지 설정 요청 (category, symbol, buyLeverage, sellLeverage)
     * @param userId  API Key를 조회할 사용자 ID
     * @return 레버리지 설정 결과 응답
     */
    public SetLeverageResponse setLeverage(SetLeverageRequest request, Long userId) {
        setApiKeyContext(userId);
        try {
            return positionClient.setLeverage(request).getBody();
        } finally {
            UserApiKeyContext.clear();
        }
    }

    /**
     * Bybit에 손절/익절(Trading Stop)을 설정한다.
     * 사용자 API Key 컨텍스트를 설정한 후 Trading Stop API를 호출한다.
     *
     * @param request 손절/익절 설정 요청 (category, symbol, takeProfit, stopLoss, positionIdx)
     * @param userId  API Key를 조회할 사용자 ID
     * @return Trading Stop 설정 결과 응답
     */
    public SetTradingStopResponse setTradingStop(SetTradingStopRequest request, Long userId) {
        setApiKeyContext(userId);
        try {
            return positionClient.setTradingStop(request).getBody();
        } finally {
            UserApiKeyContext.clear();
        }
    }

    /**
     * 사용자 API Key/Secret을 복호화하여 ThreadLocal 컨텍스트에 설정한다.
     *
     * @param userId 사용자 ID
     */
    private void setApiKeyContext(Long userId) {
        User user = loginRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ExceptionMessage.USER_NOT_FOUND));

        UserApiKeyContext.set(
                aesEncryptor.decrypt(user.getApiKey()),
                aesEncryptor.decrypt(user.getApiSecret())
        );
    }
}
