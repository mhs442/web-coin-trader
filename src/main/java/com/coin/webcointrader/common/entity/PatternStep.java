package com.coin.webcointrader.common.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "pattern_step")
@Getter
@Setter
@NoArgsConstructor
public class PatternStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;                    // 단계 PK

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "queue_id", nullable = false)
    private PatternQueue queue;         // 소속 패턴 큐

    @Column(nullable = false)
    private int stepLevel;              // 단계 레벨 (1부터 시작)

    @Column(nullable = false)
    private boolean isFull = false;     // 패턴이 최대 수(2개)로 가득 찬 상태 여부

    @OneToMany(mappedBy = "step", cascade = CascadeType.ALL, orphanRemoval = true)
    @BatchSize(size = 50)  // N+1 방지: 여러 단계의 patterns를 건별 조회 대신 IN절로 배치 조회
    private List<Pattern> patterns = new ArrayList<>();  // 소속 패턴 목록
}
