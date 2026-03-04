package com.coin.webcointrader.common.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "pattern_block")
@Getter
@Setter
@NoArgsConstructor
public class PatternBlock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;                    // 블록 PK

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pattern_id", nullable = false)
    private Pattern pattern;            // 소속 패턴

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Side side;                  // 매매 방향 (LONG / SHORT)

    @Column(nullable = false)
    private int blockOrder;             // 블록 실행 순서

    @Column(nullable = false)
    private boolean isLeaf = false;     // 실행(리프) 블록 여부 (true: 마지막 실행 블록)
}
