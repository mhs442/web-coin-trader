package com.coin.webcointrader.common.dto;

import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public abstract class BybitMasterDTO {
    private String retCode;     // 응답 코드 ("0": 정상)
    private String retMsg;      // 응답 메시지 (예: "OK", 오류 설명)
}
