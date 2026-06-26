package com.kyuloud.ai.agent.unified;

/**
 * Phase 7 — {@code GET /api/agent/models} 응답 항목. 프론트가 모델 선택 UI 를 구성하는 데 쓴다.
 *
 * @param name        Ollama 모델 태그(요청 {@code model} 에 그대로 넣는 값)
 * @param displayName 사람이 읽는 표시명
 * @param isDefault   요청에 model 미지정 시 쓰이는 기본 모델 여부
 */
public record ModelInfo(
        String name,
        String displayName,
        boolean isDefault
) {
}
