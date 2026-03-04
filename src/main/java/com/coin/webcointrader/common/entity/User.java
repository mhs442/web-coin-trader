package com.coin.webcointrader.common.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;                    // 사용자 PK

    @Column(nullable = false, length = 20)
    private String username;            // 사용자 이름

    @Column(nullable = false, unique = true, length = 20)
    private String phoneNumber;         // 휴대폰 번호 (로그인 ID)

    @Column(nullable = false, unique = true, length = 50)
    private String email;               // 이메일 주소

    @Column(nullable = false, length = 200)
    private String password;            // BCrypt 해싱된 비밀번호

    @Column(nullable = false, length = 500)
    private String apiKey;              // AES-256 암호화된 Bybit API Key

    @Column(nullable = false, length = 500)
    private String apiSecret;           // AES-256 암호화된 Bybit API Secret
}
