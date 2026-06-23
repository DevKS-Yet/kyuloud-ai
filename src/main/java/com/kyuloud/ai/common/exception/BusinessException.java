package com.kyuloud.ai.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 비즈니스 로직 상 의도적으로 발생시키는 예외.
 * GlobalExceptionHandler 에서 code/message/status 를 그대로 응답에 사용한다.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public BusinessException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
