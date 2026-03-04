package com.coin.webcointrader.common.dto.response;

import com.coin.webcointrader.common.dto.ByBItMasterDTO;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class OrderBookResponse extends ByBItMasterDTO {

    private Result result;

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class Result {
        private String s; // Symbol
        @JsonProperty("b")
        private List<String[]> b; // Bids (매수 호가) [[price, size], ...]
        @JsonProperty("a")
        private List<String[]> a; // Asks (매도 호가) [[price, size], ...]
        private long ts; // Timestamp
        private long u; // Update ID
    }
}