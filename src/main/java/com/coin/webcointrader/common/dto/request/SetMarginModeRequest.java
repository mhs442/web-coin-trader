package com.coin.webcointrader.common.dto.request;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Bybit POST /v5/account/set-margin-mode 요청 DTO.
 * UTA 계정의 마진 모드를 변경한다.
 * 열린 포지션/주문이 없는 상태에서만 변경 가능하다.
 */
@Getter
@AllArgsConstructor
public class SetMarginModeRequest {
    private String setMarginMode;   // 마진 모드 ("ISOLATED_MARGIN", "REGULAR_MARGIN", "PORTFOLIO_MARGIN")
}
