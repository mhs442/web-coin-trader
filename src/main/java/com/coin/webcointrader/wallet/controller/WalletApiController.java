package com.coin.webcointrader.wallet.controller;

import com.coin.webcointrader.common.dto.UserDTO;
import com.coin.webcointrader.common.dto.response.GetWalletBalanceResponse;
import com.coin.webcointrader.wallet.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 지갑 잔고 조회 API 컨트롤러.
 * 프론트에서 5초 간격으로 폴링하여 잔고를 표시한다.
 */
@RestController
@RequestMapping("/api/wallet")
@RequiredArgsConstructor
public class WalletApiController {

    private final WalletService walletService;

    /**
     * 로그인한 사용자의 UTA 지갑 잔고를 조회한다.
     *
     * @param user 인증된 사용자 정보
     * @return 지갑 잔고 응답
     */
    @GetMapping("/balance")
    public GetWalletBalanceResponse getBalance(@AuthenticationPrincipal UserDTO user) {
        return walletService.getWalletBalance(user.getId());
    }
}
