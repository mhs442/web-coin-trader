package com.coin.webcointrader.login.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SignupRequest {
    private String username;        // 사용자 이름
    private String phoneNumber;     // 휴대폰 번호 (로그인 ID)
    private String password;        // 비밀번호
    private String passwordConfirm; // 비밀번호 확인
    private String email;           // 이메일
    private String apiKey;          // Bybit API Key
    private String apiSecret;       // Bybit API Secret
}
