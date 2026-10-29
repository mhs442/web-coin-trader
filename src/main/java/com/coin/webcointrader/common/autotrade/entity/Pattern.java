package com.coin.webcointrader.common.autotrade.entity;

import com.coin.webcointrader.common.enums.Side;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "pattern", uniqueConstraints = @UniqueConstraint(
        name = "uk_pattern_queue_step_order", columnNames = {"queue_id", "step_level", "pattern_order"}))
@Getter
@Setter
@NoArgsConstructor
public class Pattern {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;                        // 패턴 PK

    @ManyToOne(fetch = FetchType.LAZY)
    // 물리 FK 생성 금지 (참조는 앱 레벨 관리)
    @JoinColumn(name = "queue_id", nullable = false, foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private PatternQueue queue;             // 소속 큐

    @Column(nullable = false)
    private int stepLevel;                  // 단계 레벨 (1~20)

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

    @Convert(converter = SideListConverter.class)
    @Column(nullable = false, length = 50)
    private List<Side> blocks = new ArrayList<>();  // 블록 방향 목록 (마지막 원소 = leaf, blockOrder = index + 1)
}
