package com.coin.webcointrader.trade.service;

import com.coin.webcointrader.client.trade.TradeClient;
import com.coin.webcointrader.common.dto.request.CreateOrderRequest;
import com.coin.webcointrader.common.dto.response.CreateOrderResponse;
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
    private final LoginRepository loginRepository;
    private final AesEncryptor aesEncryptor;

    /**
     * Bybit에 주문을 실행한다.
     * category가 없으면 LINEAR로 기본 설정하고,
     * 사용자 API Key/Secret을 복호화하여 {@link com.coin.webcointrader.common.util.UserApiKeyContext}에 설정 후 주문을 전송한다.
     * 완료 여부와 관계없이 finally 블록에서 컨텍스트를 정리한다.
     *
     * @param request 주문 요청 (symbol, side, orderType, qty 등 포함, category 생략 가능)
     * @param userId  API Key를 조회할 사용자 ID
     * @return 주문 생성 응답 (Bybit 주문 ID 포함)
     * @throws CustomException 사용자를 찾을 수 없는 경우 (USER_NOT_FOUND)
     */
    public CreateOrderResponse placeOrder(CreateOrderRequest request, Long userId) {
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

        // 사용자 API Key 로드 및 복호화
        User user = loginRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ExceptionMessage.USER_NOT_FOUND));

        UserApiKeyContext.set(
                aesEncryptor.decrypt(user.getApiKey()),
                aesEncryptor.decrypt(user.getApiSecret())
        );

        try {
            return tradeClient.createOrder(request).getBody();
        } finally {
            UserApiKeyContext.clear();
        }
    }
}
