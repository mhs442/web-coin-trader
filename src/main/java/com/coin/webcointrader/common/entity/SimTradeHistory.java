package com.coin.webcointrader.common.entity;

import com.coin.webcointrader.common.enums.OrderResult;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 모의투자 거래 히스토리 엔티티.
 * sim_trade_history 테이블에 독립 저장된다.
 * TradeHistory와 동일한 구조이나, 상속 없이 독립 엔티티로 관리한다.
 */
@Entity
@Table(name = "sim_trade_history")
@Getter
@Setter
@NoArgsConstructor
public class SimTradeHistory extends BaseEntity {

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
    private BigDecimal amount;          // 주문 금액 (USDT)

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal executedPrice;   // 체결 가격

    @Column(length = 100)
    private String orderId;             // 주문 ID (SIM- 접두어)

    @Column(nullable = false, length = 20)
    private String orderType;           // 주문 유형 (ENTRY / SELL / LIQUIDATION)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private OrderResult orderResult;    // SUCCESS / FAILED

    @Column(columnDefinition = "TEXT")
    private String errorMessage;        // 오류 메시지 (FAILED 시 기록)
}
