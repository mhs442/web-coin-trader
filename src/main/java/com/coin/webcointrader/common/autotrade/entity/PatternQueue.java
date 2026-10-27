package com.coin.webcointrader.common.autotrade.entity;

import com.coin.webcointrader.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;

import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.enums.TradeMode;

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExchangeType exchangeType;  // 거래소 타입 (기본값 없음 — 생성 시 반드시 지정)

    @Column(nullable = false)
    private boolean isActive = false;             // 활성화 여부 (Y: 활성, N: 비활성)

    @Column
    private LocalDateTime activatedAt;      // 활성화 일시

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal triggerRate;         // 트리거 기준 상승률 (%)

    @Column(nullable = false)
    private boolean isFull = false;         // 모든 단계가 가득 찬 상태 여부

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TradeMode tradeMode = TradeMode.MAIN;         // 거래 모드 (MAIN: 실전, SIM: 모의)

    @Column(nullable = false)
    private boolean cycle = false;                        // 단계 소진 후 1단계 재시작 여부 (true: 반복, false: 큐 비활성화)

    @OneToMany(mappedBy = "queue", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("stepLevel ASC, patternOrder ASC")
    @BatchSize(size = 50)  // N+1 방지: 여러 큐의 patterns를 건별 조회 대신 IN절로 배치 조회
    private List<Pattern> patterns = new ArrayList<>();  // 소속 패턴 목록 (단계 → 패턴 순서 정렬)
}
