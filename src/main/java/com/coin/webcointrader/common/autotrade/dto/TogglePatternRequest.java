package com.coin.webcointrader.common.autotrade.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 큐 켜기/끄기 요청 DTO.
 * 현재 상태를 뒤집지 않고 원하는 상태를 명시해, 오래된 화면의 요청이 반대로 동작하지 않게 한다.
 */
@Getter
@Setter
public class TogglePatternRequest {
    private Boolean active; // 원하는 큐 상태 (true = 켜기, false = 끄기, 필수)
}
