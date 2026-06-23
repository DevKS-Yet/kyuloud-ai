package com.kyuloud.ai.chat.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 단발 채팅 요청.
 *
 * @param message 사용자 입력 메시지
 */
public record ChatRequest(
        @NotBlank(message = "message는 비어 있을 수 없습니다.")
        String message
) {
}
