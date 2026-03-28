package com.coin.webcointrader.common.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 모의투자 투자 히스토리 엔티티.
 * sim_investment_history 테이블에 독립 저장된다.
 * InvestmentHistory와 동일한 구조이나, 상속 없이 독립 엔티티로 관리한다.
 */
@Entity
@Table(name = "sim_investment_history")
@Getter
@Setter
@NoArgsConstructor
public class SimInvestmentHistory extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;                // 사용자 id

    @Column(nullable = false)
    private Long patternStepId;         // 패턴별 단계 id (참조용, FK 없음)

    @Column(nullable = false, length = 100)
    private String symbol;              // 코인 종류

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Side side;                  // 투자 방향 (LONG / SHORT)

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal profitLoss;      // 손익금

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal entryPrice;      // 진입가

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal exitPrice;       // 청산가

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal amount;          // 투입 금액 (USDT)
}
