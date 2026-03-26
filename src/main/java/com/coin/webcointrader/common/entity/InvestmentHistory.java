package com.coin.webcointrader.common.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 투자 히스토리 엔티티.
 * 포지션의 전체 사이클(진입 → 매도 성공/청산) 결과를 스냅샷으로 저장한다.
 * 원본 삭제와 무관하게 투자 기록을 보존하기 위해 FK 없이 독립 저장한다.
 */
@Entity
@Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
@Table(name = "investment_history")
@Getter
@Setter
@NoArgsConstructor
public class InvestmentHistory extends BaseEntity {

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
