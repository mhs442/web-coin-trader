package com.coin.webcointrader.common.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "pattern_history")
@Getter
@Setter
@NoArgsConstructor
public class PatternHistory extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;                // 히스토리 PK

    @Column(nullable = false)
    private Long patternStepId;     // 참조용 (FK 없음)

    @Column(nullable = false)
    private Long userId;            // 스냅샷

    @Column(nullable = false, length = 100)
    private String symbol;          // 스냅샷

    @Column(nullable = false, length = 10)
    private String action;          // create / update / delete

    @Column(nullable = false, columnDefinition = "TEXT")
    private String blockList;       // 패턴 단계 블록 집합 (JSON)
}
