package com.coin.webcointrader.common.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 모의투자 거래 히스토리 엔티티.
 * TradeHistory를 상속하며, sim_trade_history 테이블에 독립 저장된다.
 * 실제 Bybit 주문이 없으므로 orderId는 사용하지 않는다.
 */
@Entity
@Table(name = "sim_trade_history")
@Getter
@Setter
@NoArgsConstructor
public class SimTradeHistory extends TradeHistory {
}
