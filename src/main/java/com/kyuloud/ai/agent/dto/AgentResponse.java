package com.kyuloud.ai.agent.dto;

import java.util.List;

/**
 * Phase 3 — Agent 채팅 응답.
 *
 * @param reply     LLM 최종 답변 텍스트
 * @param toolsUsed 이번 응답에서 LLM이 호출한 도구 이름 목록(호출 순서, tool-call trace)
 */
public record AgentResponse(
        String reply,
        List<String> toolsUsed
) {
}
