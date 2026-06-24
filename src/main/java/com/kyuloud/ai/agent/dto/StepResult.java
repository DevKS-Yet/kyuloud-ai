package com.kyuloud.ai.agent.dto;

/**
 * Phase 3d-4 — 계획 단계의 실행 결과.
 *
 * @param order       실행된 단계 순서
 * @param description 실행한 단계 설명(원 계획과 동일)
 * @param result      해당 단계를 도구 탑재 에이전트로 실행한 결과 텍스트
 */
public record StepResult(
        int order,
        String description,
        String result
) {
}
