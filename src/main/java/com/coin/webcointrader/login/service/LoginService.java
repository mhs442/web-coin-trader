package com.coin.webcointrader.login.service;

import com.coin.webcointrader.common.dto.UserDTO;
import com.coin.webcointrader.common.entity.User;
import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.util.AesEncryptor;
import com.coin.webcointrader.login.dto.SignupRequest;
import com.coin.webcointrader.login.repository.LoginRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring Security 사용자 인증 서비스.
 * 전화번호를 username으로 사용하여 인증을 처리한다.
 */
@Service
@RequiredArgsConstructor
public class LoginService implements UserDetailsService {
    private final AesEncryptor aesEncryptor;
    private final BCryptPasswordEncoder bCryptPasswordEncoder;
    private final BybitApiKeyValidator bybitApiKeyValidator;
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

    /**
     * 회원가입을 처리한다.
     * 비밀번호 일치 확인 → 전화번호 중복 확인 → Bybit API Key 유효성 검증 → 사용자 저장 순서로 진행된다.
     * 비밀번호는 BCrypt로 해싱하고, API Key/Secret은 AES-256으로 암호화하여 저장한다.
     *
     * @param request 회원가입 요청 (username, phoneNumber, password, passwordConfirm, apiKey, apiSecret 포함)
     * @throws CustomException 비밀번호 불일치(PASSWORD_MISMATCH), 전화번호 중복(DUPLICATE_PHONE_NUMBER),
     *                         API Key 무효(INVALID_API_KEY) 시 예외 발생
     */
    @Transactional
    public void signup(SignupRequest request) {
        // 1. 비밀번호 일치 확인
        if (!request.getPassword().equals(request.getPasswordConfirm())) {
            throw new CustomException(ExceptionMessage.PASSWORD_MISMATCH);
        }

        // 2. 휴대폰 번호 중복 확인
        if (loginRepository.findByPhoneNumber(request.getPhoneNumber()).isPresent()) {
            throw new CustomException(ExceptionMessage.DUPLICATE_PHONE_NUMBER);
        }

        // 3. Bybit API Key 유효성 검증
        boolean isValid = bybitApiKeyValidator.validate(request.getApiKey(), request.getApiSecret());
        if (!isValid) {
            throw new CustomException(ExceptionMessage.INVALID_API_KEY);
        }

        // 4. User 저장 (비밀번호 BCrypt 해싱, API Key/Secret AES 암호화)
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPhoneNumber(request.getPhoneNumber());
        user.setPassword(bCryptPasswordEncoder.encode(request.getPassword()));
        user.setEmail(request.getEmail());
        user.setApiKey(aesEncryptor.encrypt(request.getApiKey()));
        user.setApiSecret(aesEncryptor.encrypt(request.getApiSecret()));

        loginRepository.save(user);
    }
}
