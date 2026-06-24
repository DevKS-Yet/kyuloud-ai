package com.kyuloud.ai.agent.planner;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.kyuloud.ai.agent.dto.PlanStep;

import java.util.List;

/**
 * Phase 3d-4 — Planner 가 수립한 실행 계획(구조화 출력 대상).
 *
 * <p>{@link PlannerService} 가 LLM 의 구조화 출력(JSON)으로 직접 생성한다. 단순 요청이면
 * 단계가 1개이고, 복잡한 multi-step 요청이면 순서가 있는 여러 단계로 분해된다.
 *
 * @param steps 순서가 있는 실행 단계 목록(order 오름차순)
 */
@JsonClassDescription("사용자 요청을 수행하기 위한 순서가 있는 실행 계획")
public record Plan(
        @JsonPropertyDescription("순서대로 수행할 단계 목록. 단순 요청이면 단계 1개로 충분하다.")
        List<PlanStep> steps
) {
}
