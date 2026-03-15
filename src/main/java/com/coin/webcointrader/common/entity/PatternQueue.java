package com.coin.webcointrader.common.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "pattern_queue")
@Getter @Setter
@NoArgsConstructor
public class PatternQueue extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;                        // 큐 PK

    @Column(nullable = false)
    private Long userId;                    // 소유자 사용자 ID

    @Column(nullable = false, length = 100)
    private String symbol;                  // 코인 심볼 (예: BTCUSDT)

    @Column(nullable = false)
    private boolean isActive = false;             // 활성화 여부 (Y: 활성, N: 비활성)

    @Column
    private LocalDateTime activatedAt;      // 활성화 일시

    @Column(nullable = false)
    private Integer triggerSeconds;         // 트리거 기준 시간 (초)

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal triggerRate;         // 트리거 기준 상승률 (%)

    @Column(nullable = false)
    private boolean isFull = false;         // 모든 단계가 가득 찬 상태 여부

    @Column
    private Long currentStepId;             // 현재 진행 중인 단계 ID (FK 없음, 앱 레벨 관리)

    @Column
    private Long currentPatternId;          // 현재 진행 중인 패턴 ID (FK 없음, 앱 레벨 관리)

    @Column
    private Integer currentBlockOrder;      // 현재 대기 중인 블록 순서 (FK 없음, 앱 레벨 관리)

    @OneToMany(mappedBy = "queue", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PatternStep> steps = new ArrayList<>();  // 소속 단계 목록
}
