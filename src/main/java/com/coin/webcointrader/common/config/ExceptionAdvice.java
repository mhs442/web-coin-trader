package com.coin.webcointrader.common.config;

import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.enums.LogMessage;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@ControllerAdvice
public class ExceptionAdvice {

    @ExceptionHandler(value = Exception.class)
    public Object defualtExceptionHandler(Exception e, HttpServletRequest request) {
        // 클라이언트에 오류를 반환해야 할 때
        if(request.getHeader("Accept")!=null && request.getHeader("Accept").contains("text/html")) {
            return "error/4xx";
        }
        // 본문 요청에 대한 응답일 때
        else{
            log.error(LogMessage.ERROR_OCCURRED.getMessage(), e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ExceptionMessage.OTHER_ERROR.getMessage());
        }
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public Object handleNotFound(NoResourceFoundException e, HttpServletRequest request) {
        if (request.getHeader("Accept") != null && request.getHeader("Accept").contains("text/html")) {
            return "error/4xx";
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ExceptionMessage.NOT_FOUND_RESOURCE.getMessage());
    }
}
