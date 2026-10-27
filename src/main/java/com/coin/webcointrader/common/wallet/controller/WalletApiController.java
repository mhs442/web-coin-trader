package com.coin.webcointrader.common.wallet.controller;

import com.coin.webcointrader.common.login.dto.UserDTO;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.enums.TradeMode;
import com.coin.webcointrader.common.trade.service.TradeFacade;
import com.coin.webcointrader.common.wallet.dto.WalletBalanceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 지갑 잔고 조회 API 컨트롤러.
 * 프론트에서 5초 간격으로 폴링하여 잔고를 표시한다.
 * 거래 모드(MAIN/SIM)에 따라 실전 지갑 또는 가상 지갑 잔고를 반환한다.
 */
@RestController
@RequestMapping("/api/wallet")
@RequiredArgsConstructor
public class WalletApiController {

    private final TradeFacade tradeFacade;

    /**
     * 로그인한 사용자의 지갑 잔고를 조회한다.
     * 거래 모드에 따라 실전(거래소 API) 또는 모의(SimWallet) 잔고를 반환한다.
     *
     * @param user     인증된 사용자 정보
     * @param mode     거래 모드 ("main" 또는 "sim", 기본값 "main")
     * @param exchange 거래소 타입 (필수, 대소문자 무관 — 미전달 시 EXCHANGE_TYPE_REQUIRED)
     * @return 지갑 잔고 응답 (totalEquity, walletBalance, availableBalance)
     */
    @GetMapping("/balance")
    public WalletBalanceResponse getBalance(@AuthenticationPrincipal UserDTO user,
                                            @RequestParam(defaultValue = "main") String mode,
                                            @RequestParam(required = false) String exchange) {
        // 거래 모드 결정
        TradeMode tradeMode = "sim".equalsIgnoreCase(mode) ? TradeMode.SIM : TradeMode.MAIN;
        // 미지원 거래소는 INVALID_EXCHANGE_TYPE(400)
        ExchangeType exchangeType = ExchangeType.from(exchange);
        return WalletBalanceResponse.from(tradeFacade.getBalance(user.getId(), exchangeType, tradeMode));
    }
}
