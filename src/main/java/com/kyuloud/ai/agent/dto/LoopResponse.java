package com.kyuloud.ai.agent.dto;

import java.util.List;

/**
 * Phase 5 — 평가 기반 에이전트 루프 응답.
 *
 * <p>질문 파악·계획 → (행동 → 평가)* → 답변 의 전 과정을 노출한다. 초기 계획과 회차별 행동·평가 기록을
 * 함께 반환해 "왜 이렇게 답했는지"(추론·검증 과정)를 투명하게 보여준다.
 *
 * @param request    원본 사용자 요청
 * @param plan       초기 계획(질문 파악 + 계획 수립 결과)
 * @param iterations 회차별 행동→평가 기록
 * @param reply      누적 근거를 종합한 최종 답변
 * @param toolsUsed  전체 과정에서 호출된 도구 이름 목록(tool-call trace)
 */
public record LoopResponse(
        String request,
        List<PlanStep> plan,
        List<IterationTrace> iterations,
        String reply,
        List<String> toolsUsed
) {
}
