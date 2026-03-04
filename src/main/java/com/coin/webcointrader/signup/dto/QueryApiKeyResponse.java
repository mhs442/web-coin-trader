package com.coin.webcointrader.signup.dto;

import com.coin.webcointrader.common.dto.ByBItMasterDTO;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class QueryApiKeyResponse extends ByBItMasterDTO {
    private Result result;      // API Key 조회 결과

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Result {
        private String id;          // API Key 내부 ID
        private String note;        // API Key 메모명
        private String apiKey;      // API Key 값
        private int readOnly;       // 읽기 전용 여부 (0: 읽기/쓰기, 1: 읽기 전용)
        private int type;           // API Key 유형 (1: 개인, 2: 서브계정)
        private int userID;         // 소유자 사용자 ID
    }
}
