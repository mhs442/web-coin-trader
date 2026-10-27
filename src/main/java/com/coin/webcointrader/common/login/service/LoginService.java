package com.coin.webcointrader.common.login.service;

import com.coin.webcointrader.common.login.dto.UserDTO;
import com.coin.webcointrader.common.login.entity.User;
import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.login.dto.SignupRequest;
import com.coin.webcointrader.common.login.repository.LoginRepository;
import com.coin.webcointrader.common.sim.service.SimWalletService;
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
    private final BCryptPasswordEncoder bCryptPasswordEncoder;
    private final ExchangeKeyRegisterService exchangeKeyRegisterService;
    private final LoginRepository loginRepository;
    private final SimWalletService simWalletService;

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
     * 비밀번호 일치 확인 → 전화번호 중복 확인 → 거래소 확인 → 사용자 저장 → 거래소 API Key 등록 순서로 진행된다.
     * 비밀번호는 BCrypt로 해싱하고, API Key 검증·암호화·저장은 {@link ExchangeKeyRegisterService}에 위임한다.
     * 키 검증이 실패하면 예외로 트랜잭션이 롤백되어 사용자도 저장되지 않는다.
     *
     * @param request 회원가입 요청 (username, phoneNumber, password, passwordConfirm,
     *                apiKey, apiSecret, exchangeType 포함; exchangeType 필수)
     * @throws CustomException 비밀번호 불일치(PASSWORD_MISMATCH), 전화번호 중복(DUPLICATE_PHONE_NUMBER),
     *                         미지원 거래소(INVALID_EXCHANGE_TYPE), API Key 무효(INVALID_API_KEY) 시 예외 발생
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

        // 3. 거래소 확인 (미입력이면 EXCHANGE_TYPE_REQUIRED, 미지원 값이면 INVALID_EXCHANGE_TYPE) — 사용자 저장 전에 판별
        ExchangeType exchangeType = ExchangeType.from(request.getExchangeType());

        // 4. User 저장 (비밀번호 BCrypt 해싱) — API Key는 UserExchangeKey로 분리 저장
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPhoneNumber(request.getPhoneNumber());
        user.setPassword(bCryptPasswordEncoder.encode(request.getPassword()));
        user.setEmail(request.getEmail());

        loginRepository.save(user);

        // 5. 거래소 API Key 검증 후 암호화 저장 (실패 시 INVALID_API_KEY → 사용자 저장까지 롤백)
        exchangeKeyRegisterService.saveKey(user.getId(), exchangeType, request.getApiKey(), request.getApiSecret());

        // 6. 모의투자 가상 지갑 생성 (기본 잔고 10,000 USDT)
        simWalletService.createWallet(user.getId());
    }
}
