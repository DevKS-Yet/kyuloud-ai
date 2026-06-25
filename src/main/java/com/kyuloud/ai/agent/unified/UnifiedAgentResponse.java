package com.kyuloud.ai.agent.unified;

import com.kyuloud.ai.agent.dto.ClarifyingQuestion;
import com.kyuloud.ai.agent.dto.StepResult;

import java.util.List;

/**
 * Phase 6 — 통합 에이전트({@code POST /api/agent}) 응답.
 *
 * <p>{@code routed} 는 Router 가 분류한 전략, {@code executed} 는 실제로 수행한 전략이다. 둘이 다를 수 있다:
 * 6a 에서는 RESEARCH 로 분류돼도 DIRECT 로 폴백하고, 6b 에서는 Router 가 CLARIFY 라 해도 명확화 전문가가
 * 되물을 게 없다고 판단하면 DIRECT 로 진행한다(라우팅 동작을 관찰하기 위해 둘 다 노출).
 *
 * <p>{@code executed == CLARIFY} 이면 답변 대신 {@code clarification}(되묻는 질문+선택지)을 채워 반환한다.
 * 클라이언트는 이를 사용자에게 보여주고, 사용자가 답한 내용을 더해 같은 {@code conversationId} 로 재호출한다.
 * 그 외 전략에서는 {@code clarification} 은 빈 목록이다.
 *
 * <p>{@code executed == RESEARCH}(6c) 이면 {@code evidence} 에 워커별 조사 근거를 함께 노출해 추론 과정을
 * 투명하게 보여준다(최종 {@code reply} 는 그 근거를 종합한 답변). DIRECT/CLARIFY 에서는 빈 목록이다.
 *
 * @param request       원본 사용자 질문
 * @param routed        Router 분류 결과
 * @param executed      실제 수행한 전략
 * @param reply         최종 답변 텍스트(CLARIFY 면 되묻기를 사람이 읽을 수 있게 렌더링한 텍스트)
 * @param clarification CLARIFY 시 되물을 질문 목록(선택지 포함), 그 외엔 빈 목록
 * @param evidence      RESEARCH 시 워커별 조사 근거(순서·목표·결과), 그 외엔 빈 목록
 * @param toolsUsed     이번 응답에서 호출된 도구 이름 목록(수집형 tracer 기준)
 */
public record UnifiedAgentResponse(
        String request,
        RouteStrategy routed,
        RouteStrategy executed,
        String reply,
        List<ClarifyingQuestion> clarification,
        List<StepResult> evidence,
        List<String> toolsUsed
) {
}
