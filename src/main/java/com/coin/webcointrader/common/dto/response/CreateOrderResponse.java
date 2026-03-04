package com.coin.webcointrader.common.dto.response;

import com.coin.webcointrader.common.dto.ByBItMasterDTO;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class CreateOrderResponse extends ByBItMasterDTO {

    private Result result;      // 주문 생성 결과

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class Result {
        private String orderId;         // Bybit에서 발급한 주문 ID
        private String orderLinkId;     // 클라이언트가 설정한 주문 ID (미사용 시 빈 문자열)
    }
}
