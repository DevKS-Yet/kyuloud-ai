package com.kyuloud.ai.agent.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Phase 3d-4 — 계획의 단일 단계.
 *
 * <p>Planner 가 사용자 요청을 분해해 만든 하나의 실행 단계. 구조화 출력(JSON)으로
 * LLM 이 직접 생성하므로 필드 설명을 함께 제공해 스키마 품질을 높인다.
 *
 * @param order       실행 순서(1부터 시작)
 * @param description 이 단계에서 수행할 작업 설명(어떤 도구가 필요한지 자연어로)
 */
public record PlanStep(
        @JsonPropertyDescription("실행 순서, 1부터 시작하는 정수")
        int order,

        @JsonPropertyDescription("이 단계에서 수행할 단일 작업에 대한 구체적 설명")
        String description
) {
}
