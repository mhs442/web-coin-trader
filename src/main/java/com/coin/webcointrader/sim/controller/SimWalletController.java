package com.coin.webcointrader.sim.controller;

import com.coin.webcointrader.common.dto.UserDTO;
import com.coin.webcointrader.sim.dto.AddBalanceRequest;
import com.coin.webcointrader.sim.service.SimWalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 모의투자 가상 지갑 API 컨트롤러.
 * 모의 지갑의 잔고 충전 기능을 제공한다.
 */
@RestController
@RequestMapping("/api/sim/wallet")
@RequiredArgsConstructor
public class SimWalletController {

    private final SimWalletService simWalletService;

    /**
     * 모의 지갑에 금액을 추가(충전)한다.
     *
     * @param user   인증된 사용자 정보
     * @param request 추가할 금액 정보
     * @return 처리 결과 메시지
     */
    @PostMapping("/add-balance")
    public ResponseEntity<String> addBalance(@AuthenticationPrincipal UserDTO user,
                                             @RequestBody AddBalanceRequest request) {
        simWalletService.addBalance(user.getId(), request.getAmount());
        return ResponseEntity.ok("충전 완료");
    }
}
