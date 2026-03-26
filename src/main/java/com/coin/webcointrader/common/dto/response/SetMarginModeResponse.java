package com.coin.webcointrader.common.dto.response;

import com.coin.webcointrader.common.dto.BybitMasterDTO;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

/**
 * Bybit POST /v5/account/set-margin-mode 응답 DTO.
 */
@Getter @Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class SetMarginModeResponse extends BybitMasterDTO {
}
