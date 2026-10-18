package com.coin.webcointrader.trade.dto;

import com.coin.webcointrader.common.enums.Side;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 투자 히스토리 내부 전달용 DTO.
 * MAIN(investment_history) / SIM(sim_investment_history) 엔티티를 도메인 밖으로 노출하지 않기 위해 사용한다.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvestmentHistoryDTO {
    private Long id;                    // 투자 히스토리 PK (저장 전에는 null)
    private Long userId;                // 사용자 ID
    private Long patternStepId;         // 패턴 단계 ID (참조용, FK 없음)
    private String symbol;              // 코인 심볼
    private Side side;                  // 투자 방향 (LONG / SHORT)
    private BigDecimal profitLoss;      // 손익금
    private BigDecimal entryPrice;      // 진입가
    private BigDecimal exitPrice;       // 청산가
    private BigDecimal amount;          // 투입 금액 (마진, 레버리지 미포함)
    private int leverage;               // 레버리지
    private BigDecimal tpPrice;         // 익절 예상가
    private BigDecimal slPrice;         // 손절 예상가
    private BigDecimal fundingFee;      // 펀딩비 (MAIN에서만 채워짐, SIM은 null)
    private LocalDateTime createdAt;    // 생성 일시 (조회 시에만 채워짐)
}
