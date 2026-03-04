package com.coin.webcointrader.common.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 패턴별 단계 엔티티.
 * 하나의 큐 안에서 실행 순서를 가지는 개별 Long/Short 단계를 나타낸다.
 */
@Entity
@Table(name = "queue_step")
@Getter
@Setter
@NoArgsConstructor
public class QueueStep {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;                    // 단계 PK

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "queue_id", nullable = false)
    private Queue queue;                // 소속 큐

    @Column(nullable = false)
    private Integer stepOrder;          // 단계 실행 순서 (오름차순)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Side side;                  // 매매 방향 (LONG / SHORT)

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal quantity;        // 주문 수량
}
