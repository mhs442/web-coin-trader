package com.coin.webcointrader.mypage.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 히스토리 차트 데이터 응답 DTO.
 * 누적 손익 라인 / 심볼별 손익 바 / 승패 도넛 차트 3종을 한 번에 반환한다.
 */
@Getter
@Builder
public class HistoryChartResponse {

    private List<DailyData> dailyData;      // 일별 누적 손익 (라인 차트)
    private List<SymbolData> symbolData;    // 심볼별 손익 합산 (바 차트)
    private long winCount;                  // 익절 건수 (도넛 차트)
    private long lossCount;                 // 손절 건수 (도넛 차트)
    private long totalCount;                // 전체 건수

    @Getter
    @Builder
    public static class DailyData {
        private String date;        // yyyy-MM-dd
        private String profitLoss;  // 해당일 손익 합산
    }

    @Getter
    @Builder
    public static class SymbolData {
        private String symbol;      // 코인 심볼
        private String profitLoss;  // 해당 심볼 손익 합산
    }
}
