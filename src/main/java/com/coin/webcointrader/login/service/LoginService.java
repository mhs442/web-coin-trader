package com.coin.webcointrader.login.service;

import com.coin.webcointrader.common.dto.UserDTO;
import com.coin.webcointrader.common.entity.User;
import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.login.repository.LoginRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;

/**
 * Spring Security 사용자 인증 서비스.
 * 전화번호를 username으로 사용하여 인증을 처리한다.
 */
@Service
@RequiredArgsConstructor
public class LoginService implements UserDetailsService {
    private final LoginRepository loginRepository;

    /**
     * Spring Security 인증 진입점.
     * 전화번호로 사용자를 조회하여 {@link UserDetails}를 반환한다.
     *
     * @param phoneNumber 로그인 시 입력한 전화번호 (Spring Security의 username 역할)
     * @return 사용자 정보 DTO (id, phoneNumber, username, password 포함)
     * @throws CustomException 사용자를 찾을 수 없는 경우 (USER_NOT_FOUND)
     */
    @Override
    public UserDTO loadUserByUsername(String phoneNumber){
        User user = loginRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new CustomException(ExceptionMessage.USER_NOT_FOUND));

        return UserDTO.builder()
                .id(user.getId())
                .phoneNumber(user.getPhoneNumber())
                .username(user.getUsername())
                .password(user.getPassword())
                .build();
    }
}
