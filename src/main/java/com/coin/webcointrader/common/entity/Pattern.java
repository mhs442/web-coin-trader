package com.coin.webcointrader.common.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

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

    @OneToMany(mappedBy = "pattern", cascade = CascadeType.ALL, orphanRemoval = true)
    @BatchSize(size = 50)  // N+1 방지: 여러 패턴의 blocks를 건별 조회 대신 IN절로 배치 조회
    private List<PatternBlock> blocks = new ArrayList<>();  // 소속 블록 목록
}
