package com.coin.webcointrader.common.login.controller;

import com.coin.webcointrader.common.ExchangeClientRegistry;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.login.dto.ExchangeKeyStatusResponse;
import com.coin.webcointrader.common.login.dto.SaveExchangeKeyRequest;
import com.coin.webcointrader.common.login.dto.UserDTO;
import com.coin.webcointrader.common.login.service.ExchangeKeyRegisterService;
import com.coin.webcointrader.common.login.service.ExchangeKeyService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 거래소 API Key 조회·등록/교체 API 컨트롤러.
 * 헤더의 거래소 선택·미등록 안내와 마이페이지 키 관리 화면에서 사용한다.
 */
@RestController
@RequestMapping("/api/exchange-keys")
@RequiredArgsConstructor
public class ExchangeKeyApiController {

    private final ExchangeKeyService exchangeKeyService;
    private final ExchangeKeyRegisterService exchangeKeyRegisterService;
    private final ExchangeClientRegistry registry;

    /**
     * 지원 거래소(클라이언트 구현이 끝난 거래소)별 API Key 등록 상태를 조회한다.
     *
     * @param user 인증된 사용자 정보
     * @return 거래소별 등록 상태 목록 (키 값 미포함)
     */
    @GetMapping
    public List<ExchangeKeyStatusResponse> getStatuses(@AuthenticationPrincipal UserDTO user) {
        return exchangeKeyService.getKeyStatuses(user.getId(), registry.supportedTypes()).stream()
                .map(ExchangeKeyStatusResponse::from)
                .toList();
    }

    /**
     * 거래소 API Key를 등록하거나 교체한다.
     *
     * @param user     인증된 사용자 정보
     * @param exchange 거래소 타입 (대소문자 무관, 미지원 값이면 400)
     * @param request  API Key/Secret
     */
    @PutMapping("/{exchange}")
    public void saveKey(@AuthenticationPrincipal UserDTO user,
                        @PathVariable String exchange,
                        @RequestBody SaveExchangeKeyRequest request) {
        exchangeKeyRegisterService.saveKey(user.getId(), ExchangeType.from(exchange),
                request.getApiKey(), request.getApiSecret());
    }
}
