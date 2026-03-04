package com.coin.webcointrader.common.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 패턴 큐 엔티티.
 * 사용자가 등록한 하나의 매매 패턴 실행 단위를 나타낸다.
 */
@Entity
@Table(name = "queue")
@Getter
@Setter
@NoArgsConstructor
public class Queue {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;                    // 큐 PK

    @Column(nullable = false)
    private Long userId;                // 소유자 사용자 ID

    @Column(nullable = false, length = 100)
    private String symbol;              // 코인 심볼 (예: BTCUSDT)

    @Column(nullable = false)
    private Integer sortOrder;          // 실행 우선순위 (오름차순)

    @Column(name = "use_yn", nullable = false, length = 2)
    private String useYn = "Y";         // 활성화 여부 (Y: 활성, N: 비활성)

    @Column(name = "del_yn", nullable = false, length = 2)
    private String delYn = "N";         // 삭제 여부 (Y: 삭제, N: 유효)

    @Column(nullable = false)
    private LocalDateTime createdAt;    // 생성 일시

    private LocalDateTime deletedAt;    // 삭제 일시 (삭제 시 기록)

    @OneToMany(mappedBy = "queue", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("stepOrder ASC")
    private List<QueueStep> steps = new ArrayList<>();  // 실행 단계 목록 (stepOrder 오름차순)
}
