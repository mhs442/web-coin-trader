package com.coin.webcointrader.common.exception;

import com.coin.webcointrader.common.enums.ExceptionMessage;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Getter
@Slf4j
public class CustomException extends RuntimeException {
    private final ExceptionMessage exceptionMessage;

    public CustomException(ExceptionMessage exceptionMessage) {
        super(exceptionMessage.getMessage());
        this.exceptionMessage = exceptionMessage;
        log.error(exceptionMessage.getMessage());
    }

    /**
     * Bybit API 원본 에러 메시지 등 상세 정보를 포함하는 생성자.
     * getMessage() 호출 시 "주문 실행 실패: Insufficient margin" 형태로 반환된다.
     */
    public CustomException(ExceptionMessage exceptionMessage, String detail) {
        super(exceptionMessage.getMessage() + ": " + detail);
        this.exceptionMessage = exceptionMessage;
        log.error("{}: {}", exceptionMessage.getMessage(), detail);
    }

    public CustomException(ExceptionMessage exceptionMessage, Throwable cause) {
        super(exceptionMessage.getMessage(), cause);
        this.exceptionMessage = exceptionMessage;
        log.error(exceptionMessage.getMessage(), cause);
    }
}
