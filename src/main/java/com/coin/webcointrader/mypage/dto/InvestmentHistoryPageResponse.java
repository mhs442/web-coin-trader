package com.coin.webcointrader.mypage.dto;

import com.coin.webcointrader.common.dto.response.PageResponse;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class InvestmentHistoryPageResponse {
    private PageResponse<InvestmentHistoryResponse> page;       // 페이징 데이터
    private InvestmentSummaryResponse summary;                   // 합산 통계
}
