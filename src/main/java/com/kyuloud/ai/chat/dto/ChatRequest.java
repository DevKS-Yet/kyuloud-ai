package com.kyuloud.ai.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 단발/스트리밍 채팅 요청.
 *
 * @param conversationId 대화 세션 식별자(선택). 없으면 서비스에서 기본값 사용.
 *                       JDBC 영속 메모리의 conversation_id 컬럼이 VARCHAR(36)이라 36자 이하로 제한한다.
 * @param message        사용자 입력 메시지
 * @param model          사용할 Ollama 모델(선택, Phase 7). {@code /api/agent} 에서 DIRECT 답변·RESEARCH 워커의
 *                       생성 모델을 고른다. 미지정 시 기본 모델. allow-list 밖이면 400. 다른 엔드포인트는 무시한다.
 */
public record ChatRequest(
        @Size(max = 36, message = "conversationId는 36자 이하여야 합니다.")
        String conversationId,

        @NotBlank(message = "message는 비어 있을 수 없습니다.")
        String message,

        String model
) {
}
