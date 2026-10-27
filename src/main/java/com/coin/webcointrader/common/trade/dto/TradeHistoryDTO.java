package com.coin.webcointrader.common.trade.dto;

import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.enums.OrderResult;
import com.coin.webcointrader.common.enums.Side;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 거래 히스토리 내부 전달용 DTO.
 * MAIN(trade_history) / SIM(sim_trade_history) 엔티티를 도메인 밖으로 노출하지 않기 위해 사용한다.
 * 주문 실패 시 주문 처리 서비스가 결과 필드를 채워 저장하므로 가변 객체로 둔다.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeHistoryDTO {
    private Long id;                    // 거래 히스토리 PK (저장 전에는 null)
    private Long patternId;             // 참조용 패턴 ID (FK 없음)
    private Long userId;                // 사용자 ID
    private String symbol;              // 코인 심볼
    private ExchangeType exchangeType;  // 거래소 타입
    private Side side;                  // 매매 방향 (LONG / SHORT)
    private BigDecimal amount;          // 주문 금액 (USDT, 마진 기준)
    private BigDecimal executedPrice;   // 체결 가격
    private String orderId;             // 주문 ID (MAIN: 거래소 주문 ID, SIM: SIM- 접두어)
    private BigDecimal fee;             // 거래 수수료 (null 가능)
    private String orderType;           // 주문 유형 (ENTRY / SELL / LIQUIDATION)
    private OrderResult orderResult;    // SUCCESS / FAILED
    private String errorMessage;        // 오류 메시지 (FAILED 시)
    private LocalDateTime createdAt;    // 생성 일시 (조회 시에만 채워짐)
}
