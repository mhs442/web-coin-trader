package com.coin.webcointrader.common.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 큐 변경 히스토리 엔티티.
 * 패턴 큐의 등록/수정/삭제 이력을 저장한다.
 */
@Entity
@Table(name = "queue_history")
@Getter
@Setter
@NoArgsConstructor
public class QueueHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;                    // 히스토리 PK

    @Column(nullable = false)
    private Long queueId;               // 참조 큐 ID (FK 없음)

    @Column(nullable = false, length = 10)
    private String action;              // 변경 유형 (INSERT / UPDATE / DELETE)

    @Column(columnDefinition = "TEXT")
    private String snapshot;            // 변경 시점 큐 상태 (JSON)

    @Column(nullable = false)
    private LocalDateTime createdAt;    // 기록 일시
}
