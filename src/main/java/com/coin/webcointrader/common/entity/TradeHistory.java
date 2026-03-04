package com.coin.webcointrader.common.entity;

import com.coin.webcointrader.common.enums.OrderResult;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 거래 히스토리 엔티티.
 * 자동매매로 실행된 주문의 스냅샷을 저장한다.
 * 원본 삭제와 무관하게 거래 기록을 보존하기 위해 FK 없이 독립 저장한다.
 */
@Entity
@Table(name = "trade_history")
@Getter
@Setter
@NoArgsConstructor
public class TradeHistory extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long queueStepId;           // 참조용 (FK 없음)

    @Column(nullable = false)
    private Long userId;                // 스냅샷

    @Column(nullable = false, length = 100)
    private String symbol;              // 스냅샷

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Side side;                  // 매매 방향 (LONG / SHORT)

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal quantity;        // 주문 수량

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal executedPrice;   // 체결 가격

    @Column(length = 100)
    private String orderId;             // Bybit 주문 ID

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private OrderResult orderResult;    // SUCCESS / FAILED

    @Column(columnDefinition = "TEXT")
    private String errorMessage;        // 오류 메시지 (FAILED 시 기록)
}
