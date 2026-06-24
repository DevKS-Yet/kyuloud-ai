package com.kyuloud.ai.agent.dto;

import java.util.List;

/**
 * Phase 3d-4 — Plan-and-Execute 응답.
 *
 * <p>다단계 작업을 "계획 → 단계별 실행 → 합성"으로 분리 수행한 전체 결과를 노출한다.
 * 계획(plan)과 단계별 실행 결과(steps)를 함께 반환해 에이전트의 추론 과정을 투명하게 보여준다.
 *
 * @param request   원본 사용자 요청
 * @param plan      Planner 가 수립한 단계 목록(계획)
 * @param steps     각 단계의 실행 결과
 * @param reply     단계 결과를 종합한 최종 답변
 * @param toolsUsed 전체 실행 과정에서 호출된 도구 이름 목록(호출 순서, tool-call trace)
 */
public record PlanResponse(
        String request,
        List<PlanStep> plan,
        List<StepResult> steps,
        String reply,
        List<String> toolsUsed
) {
}
