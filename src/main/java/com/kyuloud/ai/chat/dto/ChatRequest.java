package com.kyuloud.ai.chat.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 단발/스트리밍 채팅 요청.
 *
 * @param conversationId 대화 세션 식별자(선택). 없으면 서비스에서 기본값 사용.
 * @param message        사용자 입력 메시지
 */
public record ChatRequest(
        String conversationId,

        @NotBlank(message = "message는 비어 있을 수 없습니다.")
        String message
) {
}
