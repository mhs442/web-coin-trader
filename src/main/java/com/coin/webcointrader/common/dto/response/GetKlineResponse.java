package com.coin.webcointrader.common.dto.response;

import com.coin.webcointrader.common.dto.BybitMasterDTO;
import lombok.Getter;

import java.util.List;

@Getter
public class GetKlineResponse extends BybitMasterDTO {
    private Result result;      // K-라인 조회 결과

    @Getter
    public static class Result {
        private String symbol;              // 종목 심볼
        private String category;            // 파생상품 카테고리
        private List<List<String>> list;    // 캔들 데이터 목록 [startTime, open, high, low, close, volume, turnover]
    }
}
