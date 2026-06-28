package com.coin.webcointrader.common.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "\"user\"")  // "user"는 SQL 표준 예약어이므로 큰따옴표로 감싸서 식별자로 인식하도록 함
// Hibernate의 create-drop DDL이 H2에서 "drop table if exists user cascade"를 실행할 때
// 큰따옴표 없이는 user가 예약어로 인식되어 "Unexpected token: USER" SQL 문법 오류가 발생함
@Getter @Setter
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
    // apiKey/apiSecret은 user_exchange_key 테이블로 분리됨 (UserExchangeKey 엔티티 참고)
}
