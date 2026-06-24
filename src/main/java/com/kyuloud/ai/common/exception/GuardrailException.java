package com.kyuloud.ai.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Phase 4c — 가드레일에 의해 요청이 차단됐을 때 발생.
 * {@link GlobalExceptionHandler} 가 400 + {@code GUARDRAIL_BLOCKED} 표준 에러 응답으로 매핑한다.
 */
public class GuardrailException extends BusinessException {

    public GuardrailException(String message) {
        super(HttpStatus.BAD_REQUEST, "GUARDRAIL_BLOCKED", message);
    }
}
