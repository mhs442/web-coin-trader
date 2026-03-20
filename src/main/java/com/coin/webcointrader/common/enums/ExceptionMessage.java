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
    INVALID_ENCRYPTION_KEY(HttpStatus.INTERNAL_SERVER_ERROR, "유효하지 않은 encryption key입니다."),
    INVALID_TRIGGER_TIME(HttpStatus.BAD_REQUEST, "트리거 시간(초)은 0초보다 커야 합니다."),
    INVALID_TRIGGER_RATE(HttpStatus.BAD_REQUEST, "트리거 비율(%)은 0보다 커야 합니다."),
    EMPTY_STEPS(HttpStatus.BAD_REQUEST, "단계를 1개 이상 입력해야 합니다."),
    EXCEED_MAX_STEPS(HttpStatus.BAD_REQUEST, "단계는 최대 20개까지 등록할 수 있습니다."),
    EMPTY_PATTERNS(HttpStatus.BAD_REQUEST, "각 단계에 패턴을 1개 이상 입력해야 합니다."),
    EXCEED_MAX_PATTERNS(HttpStatus.BAD_REQUEST, "패턴은 단계당 최대 2개까지 등록할 수 있습니다."),
    EXCEED_MAX_CONDITION_BLOCKS(HttpStatus.BAD_REQUEST, "조건 블록은 최대 5개까지 등록할 수 있습니다."),
    INVALID_AMOUNT(HttpStatus.BAD_REQUEST, "금액은 0보다 커야 합니다."),
    INVALID_LEVERAGE(HttpStatus.BAD_REQUEST, "레버리지는 0보다 커야 합니다."),
    MISSING_LEAF_BLOCK(HttpStatus.BAD_REQUEST, "리프 블록은 필수입니다."),
    INVALID_SIDE(HttpStatus.BAD_REQUEST, "방향은 LONG 또는 SHORT만 입력할 수 있습니다."),
    EXCEED_MAX_QUEUES(HttpStatus.BAD_REQUEST, "큐는 심볼당 최대 20개까지 등록할 수 있습니다."),
    QUEUE_NOT_FOUND(HttpStatus.BAD_REQUEST, "큐를 찾을 수 없습니다."),
    QUEUE_UNAUTHORIZED(HttpStatus.FORBIDDEN, "큐에 대한 권한이 없습니다."),
    OTHER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "오류가 발생하였습니다."),
    NOT_FOUND_RESOURCE(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다.")
    ;

    private final HttpStatus status;
    private final String message;
}
