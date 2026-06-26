package com.coin.webcointrader.common.entity;

import com.coin.webcointrader.common.enums.ExchangeType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 사용자별 거래소 API Key 엔티티.
 * User 테이블에서 분리하여 거래소 타입별로 개별 관리한다.
 * (userId, exchangeType) 조합은 UNIQUE 제약으로 보호된다.
 */
@Entity
@Table(name = "user_exchange_key",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "exchange_type"}))
@Getter @Setter
@NoArgsConstructor
public class UserExchangeKey extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;                        // PK

    @Column(name = "user_id", nullable = false)
    private Long userId;                    // 소유자 사용자 ID (FK 없음, 앱 레벨 관리)

    @Enumerated(EnumType.STRING)
    @Column(name = "exchange_type", nullable = false, length = 20)
    private ExchangeType exchangeType;      // 거래소 타입 (예: BYBIT)

    @Column(nullable = false, length = 500)
    private String apiKey;                  // AES-256 암호화된 API Key

    @Column(nullable = false, length = 500)
    private String apiSecret;               // AES-256 암호화된 API Secret
}
