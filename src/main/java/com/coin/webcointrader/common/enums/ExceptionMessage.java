package com.coin.webcointrader.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@AllArgsConstructor
@Getter
public enum ExceptionMessage {
    USER_NOT_FOUND(HttpStatus.BAD_REQUEST, "사용자를 찾을 수 없습니다."),
    SIGNATURE_GENERATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "Bybit 서명에 실패하였습니다."),
    PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST, "비밀번호가 일치하지 않습니다."),
    DUPLICATE_PHONE_NUMBER(HttpStatus.CONFLICT, "이미 등록된 휴대폰 번호입니다."),
    INVALID_API_KEY(HttpStatus.BAD_REQUEST, "유효하지 않은 Bybit API Key입니다."),
    ENCRYPTION_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "암호화에 실패하였습니다."),
    DECRYPTION_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "복호화에 실패하였습니다."),
    INVALID_ENCRYPTION_KEY(HttpStatus.INTERNAL_SERVER_ERROR, "유효하지 않은 encryption key입니다.");

    private final HttpStatus status;
    private final String message;
}
