package com.coin.webcointrader.common.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "pattern")
@Getter
@Setter
@NoArgsConstructor
public class Pattern {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;                        // 패턴 PK

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "step_id", nullable = false)
    private PatternStep step;               // 소속 단계

    @Column(nullable = false)
    private int leverage;                   // 레버리지 배수

    @Column(precision = 5, scale = 2)
    private BigDecimal stopLossRate;        // 손절 비율 (%, null이면 미설정)

    @Column(precision = 5, scale = 2)
    private BigDecimal takeProfitRate;      // 익절 비율 (%, null이면 미설정)

    @Column(precision = 10, scale = 2)
    private BigDecimal amount;              // 주문 금액 (USDT 기준)

    @Column(nullable = false)
    private int patternOrder;               // 단계 내 패턴 실행 순서
}
