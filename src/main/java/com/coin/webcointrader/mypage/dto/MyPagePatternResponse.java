package com.coin.webcointrader.mypage.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class MyPagePatternResponse {
    private Long id;                    // 큐 PK
    private String symbol;              // 종목 심볼
    private int sortOrder;              // 실행 우선순위
    private String useYn;               // 활성화 여부 (Y/N)
    private String createdAt;           // 등록 일시 (yyyy-MM-dd HH:mm:ss)
    private List<StepResponse> steps;   // 단계 목록

    @Getter
    @Builder
    public static class StepResponse {
        private int stepOrder;      // 단계 순서
        private String side;        // 매매 방향 (LONG / SHORT)
        private String quantity;    // 주문 수량
    }
}
