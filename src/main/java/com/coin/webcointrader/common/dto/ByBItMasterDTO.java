package com.coin.webcointrader.common.dto;

import lombok.Getter;

@Getter
public abstract class ByBItMasterDTO {
    private String retCode;     // 응답 코드 ("0": 정상)
    private String retMsg;      // 응답 메시지 (예: "OK", 오류 설명)
}
