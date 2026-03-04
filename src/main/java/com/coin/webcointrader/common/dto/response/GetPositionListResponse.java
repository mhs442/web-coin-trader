package com.coin.webcointrader.common.dto.response;

import com.coin.webcointrader.common.dto.ByBItMasterDTO;
import lombok.Getter;

import java.util.List;

@Getter
public class GetPositionListResponse extends ByBItMasterDTO {
    private Result result;      // 포지션 조회 결과

    @Getter
    public static class Result {
        private String category;            // 파생상품 카테고리 (예: "linear")
        private List<PositionInfo> list;    // 포지션 목록

        @Getter
        public static class PositionInfo {
            private String symbol;          // 종목 심볼 (예: "BTCUSDT")
            private String side;            // 포지션 방향 (Buy / Sell)
            private String size;            // 포지션 수량
            private String avgPrice;        // 평균 진입 가격
            private String unrealisedPnl;   // 미실현 손익
            private String cumRealisedPnl;  // 누적 실현 손익
            private String leverage;        // 레버리지 배수
            private String positionValue;   // 포지션 가치 (USDT 기준)
            private int positionIdx;        // 포지션 방향 인덱스 (0: 단방향, 1: Buy, 2: Sell)
        }
    }
}
