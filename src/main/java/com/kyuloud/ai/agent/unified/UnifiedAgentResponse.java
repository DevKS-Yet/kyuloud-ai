package com.kyuloud.ai.agent.unified;

import java.util.List;

/**
 * Phase 6 — 통합 에이전트({@code POST /api/agent}) 응답.
 *
 * <p>{@code routed} 는 Router 가 분류한 전략, {@code executed} 는 실제로 수행한 전략이다. 6a 단계에서는
 * RESEARCH/CLARIFY 로 분류돼도 일단 DIRECT 로 폴백하므로 둘이 다를 수 있다(라우팅 동작을 검증·관찰하기 위해 함께 노출).
 * 이후 단계에서 각 경로가 구현되면 정상 분류 시 둘이 일치한다.
 *
 * @param request   원본 사용자 질문
 * @param routed    Router 분류 결과
 * @param executed  실제 수행한 전략
 * @param reply     최종 답변 텍스트
 * @param toolsUsed 이번 응답에서 호출된 도구 이름 목록(수집형 tracer 기준)
 */
public record UnifiedAgentResponse(
        String request,
        RouteStrategy routed,
        RouteStrategy executed,
        String reply,
        List<String> toolsUsed
) {
}
