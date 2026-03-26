package com.coin.webcointrader.common.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 모의투자 가상 지갑 엔티티.
 * 사용자당 1개, 회원가입 시 자동 생성된다.
 */
@Entity
@Table(name = "sim_wallet")
@Getter
@Setter
@NoArgsConstructor
public class SimWallet extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;                                              // 소유자

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal balance = new BigDecimal("10000.00");        // 가상 잔고 (USDT, 기본값 10,000)
}
