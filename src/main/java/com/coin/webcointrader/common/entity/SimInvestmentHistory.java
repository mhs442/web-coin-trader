package com.coin.webcointrader.common.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 모의투자 투자 히스토리 엔티티.
 * InvestmentHistory를 상속하며, sim_investment_history 테이블에 독립 저장된다.
 */
@Entity
@Table(name = "sim_investment_history")
@Getter
@Setter
@NoArgsConstructor
public class SimInvestmentHistory extends InvestmentHistory {
}
