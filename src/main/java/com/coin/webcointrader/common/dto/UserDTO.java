package com.coin.webcointrader.common.dto;

import lombok.Builder;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

@Getter
@Builder
public class UserDTO implements UserDetails {
    private Long id;                // 사용자 PK
    private String phoneNumber;     // 휴대폰 번호 (Spring Security username)
    private String password;        // BCrypt 해싱된 비밀번호
    private String username;        // 사용자 이름

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public String getPassword() {
        return this.password;
    }

    @Override
    public String getUsername() {
        return this.phoneNumber;
    }
}
