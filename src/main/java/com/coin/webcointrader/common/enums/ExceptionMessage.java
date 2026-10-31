package com.coin.webcointrader.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@AllArgsConstructor
@Getter
public enum ExceptionMessage {
    USER_NOT_FOUND(HttpStatus.BAD_REQUEST, "사용자를 찾을 수 없습니다."),
    SIGNATURE_GENERATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "API 서명에 실패하였습니다."),
    PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST, "비밀번호가 일치하지 않습니다."),
    DUPLICATE_PHONE_NUMBER(HttpStatus.CONFLICT, "이미 등록된 휴대폰 번호입니다."),
    INVALID_API_KEY(HttpStatus.BAD_REQUEST, "유효하지 않은 거래소 API Key입니다."),
    ENCRYPTION_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "암호화에 실패하였습니다."),
    DECRYPTION_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "복호화에 실패하였습니다."),
    INVALID_ENCRYPTION_KEY(HttpStatus.INTERNAL_SERVER_ERROR, "유효하지 않은 encryption key입니다."),
    INVALID_TRIGGER_RATE(HttpStatus.BAD_REQUEST, "트리거 비율(%)은 0보다 커야 합니다."),
    EMPTY_STEPS(HttpStatus.BAD_REQUEST, "단계를 1개 이상 입력해야 합니다."),
    EXCEED_MAX_STEPS(HttpStatus.BAD_REQUEST, "단계는 최대 20개까지 등록할 수 있습니다."),
    INVALID_STEP_LEVEL(HttpStatus.BAD_REQUEST, "패턴의 단계 번호는 1 이상이어야 합니다."),
    INVALID_PATTERN_COUNT(HttpStatus.BAD_REQUEST, "각 단계에는 패턴이 정확히 2개 필요합니다 (LONG 시작 1개 + SHORT 시작 1개)."),
    NON_CONSECUTIVE_STEP_LEVEL(HttpStatus.BAD_REQUEST, "단계 번호는 1부터 빠짐없이 이어져야 합니다."),
    DUPLICATE_START_BLOCK(HttpStatus.BAD_REQUEST, "같은 단계 내 두 패턴의 시작 블록은 서로 달라야 합니다 (LONG 시작 1개 + SHORT 시작 1개)."),
    EMPTY_CONDITION_BLOCKS(HttpStatus.BAD_REQUEST, "조건 블록을 1개 이상 입력해야 합니다."),
    EXCEED_MAX_CONDITION_BLOCKS(HttpStatus.BAD_REQUEST, "조건 블록은 최대 5개까지 등록할 수 있습니다."),
    INVALID_AMOUNT(HttpStatus.BAD_REQUEST, "투자금은 0보다 커야 합니다."),
    INVALID_LEVERAGE(HttpStatus.BAD_REQUEST, "레버리지는 0보다 커야 합니다."),
    INVALID_STOP_LOSS_RATE(HttpStatus.BAD_REQUEST, "손절 비율은 0% 초과 100% 미만이어야 합니다."),
    INVALID_TAKE_PROFIT_RATE(HttpStatus.BAD_REQUEST, "익절 비율은 0%보다 커야 합니다."),
    STOP_LOSS_EXCEEDS_LIQUIDATION(HttpStatus.BAD_REQUEST, "손절률이 강제청산 한도(100 - 1.5 × 레버리지 %)를 넘습니다. 손절률 또는 레버리지를 낮춰 주세요."),
    STOP_LOSS_REQUIRED_FOR_LEVERAGE(HttpStatus.BAD_REQUEST, "11배 이상은 기본 손절(가격 8%)이 강제청산 한도를 넘습니다. 손절률을 입력해 주세요."),
    MISSING_LEAF_BLOCK(HttpStatus.BAD_REQUEST, "리프 블록은 필수입니다."),
    INVALID_SIDE(HttpStatus.BAD_REQUEST, "방향은 LONG 또는 SHORT만 입력할 수 있습니다."),
    EXCEED_MAX_QUEUES(HttpStatus.BAD_REQUEST, "큐는 심볼당 1개만 등록할 수 있습니다."),
    INVALID_EXCHANGE_TYPE(HttpStatus.BAD_REQUEST, "지원하지 않는 거래소입니다."),
    EXCHANGE_TYPE_REQUIRED(HttpStatus.BAD_REQUEST, "거래소를 선택해 주세요."),
    SYMBOL_NOT_TRADABLE(HttpStatus.BAD_REQUEST, "선택한 거래소에서 거래할 수 없는 종목입니다."),
    QUEUE_NOT_FOUND(HttpStatus.BAD_REQUEST, "큐를 찾을 수 없습니다."),
    QUEUE_UNAUTHORIZED(HttpStatus.FORBIDDEN, "큐에 대한 권한이 없습니다."),
    QUEUE_ACTIVE_CANNOT_UPDATE(HttpStatus.BAD_REQUEST, "활성화된 큐는 수정할 수 없습니다. 먼저 비활성화 해주세요."),
    QUEUE_ACTIVE_CANNOT_DELETE(HttpStatus.BAD_REQUEST, "활성화된 큐는 삭제할 수 없습니다. 먼저 비활성화 해주세요."),
    QUEUE_POSITION_CLOSE_FAILED(HttpStatus.BAD_REQUEST, "포지션 청산에 실패해 큐를 유지합니다"),
    ORDER_FAILED(HttpStatus.BAD_REQUEST, "주문 실행 실패"),
    MARKET_DATA_FAILED(HttpStatus.BAD_GATEWAY, "시세 조회 실패"),
    SET_LEVERAGE_FAILED(HttpStatus.BAD_REQUEST, "레버리지 설정 실패"),
    SWITCH_MARGIN_MODE_FAILED(HttpStatus.BAD_REQUEST, "마진 모드 변경 실패"),
    OTHER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "오류가 발생하였습니다."),
    NOT_FOUND_RESOURCE(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    EXCHANGE_NOT_SUPPORTED(HttpStatus.BAD_REQUEST, "지원하지 않는 거래소입니다."),
    API_KEY_NOT_FOUND(HttpStatus.BAD_REQUEST, "거래소 API Key를 찾을 수 없습니다."),
    GET_WALLET_BALANCE_FAILED(HttpStatus.BAD_REQUEST, "지갑 잔고 조회 실패"),
    EXCHANGE_KEY_CHANGE_BLOCKED(HttpStatus.CONFLICT, "자동매매가 실행 중입니다. 자동매매를 중지한 뒤 교체해 주세요.")
    ;

    private final HttpStatus status;
    private final String message;
}
