package com.coin.webcointrader.common.exception;

import com.coin.webcointrader.common.enums.ExceptionMessage;
import lombok.Getter;

@Getter
public class CustomException extends RuntimeException {
    private final ExceptionMessage exceptionMessage;

    public CustomException(ExceptionMessage exceptionMessage) {
        super(exceptionMessage.getMessage());
        this.exceptionMessage = exceptionMessage;
    }

    public CustomException(ExceptionMessage exceptionMessage, Throwable cause) {
        super(exceptionMessage.getMessage(), cause);
        this.exceptionMessage = exceptionMessage;
    }
}
