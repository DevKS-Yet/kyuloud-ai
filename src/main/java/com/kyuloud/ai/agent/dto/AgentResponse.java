package com.kyuloud.ai.agent.dto;

/**
 * Phase 3 — Agent 채팅 응답.
 *
 * <p>현재는 최종 답변만 반환한다. (Phase 3c에서 호출된 도구 추적(tool-call trace) 확장 예정)
 *
 * @param reply LLM 최종 답변 텍스트
 */
public record AgentResponse(
        String reply
) {
}
