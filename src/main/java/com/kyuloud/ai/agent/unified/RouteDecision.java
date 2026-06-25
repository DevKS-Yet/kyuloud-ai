package com.kyuloud.ai.agent.unified;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Phase 6 — Router 의 구조화 출력. 약한 모델이 한 번의 호출로 전략 하나와 그 근거를 낸다.
 *
 * @param strategy 선택한 처리 전략(DIRECT/RESEARCH/CLARIFY)
 * @param reason   그 전략을 고른 짧은 근거(로그·디버깅용; 답변 품질에는 무관)
 */
public record RouteDecision(
        @JsonPropertyDescription("처리 전략. 단발이면 DIRECT, 여러 단계 조사·종합이 필요하면 RESEARCH, "
                + "핵심 정보가 없어 되물어야 하면 CLARIFY 중 하나")
        RouteStrategy strategy,

        @JsonPropertyDescription("그 전략을 고른 한 문장 근거")
        String reason
) {
}
