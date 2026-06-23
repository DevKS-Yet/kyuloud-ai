package com.kyuloud.ai.chat.dto;

/**
 * 단발 채팅 응답.
 *
 * @param reply LLM 응답 텍스트
 */
public record ChatResponse(
        String reply
) {
}
