package com.kyuloud.ai.agent.eval;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Phase 5 — 평가자(Evaluator)의 충분성 판정 결과(구조화 출력 대상).
 *
 * <p>{@link EvaluatorService} 가 "지금까지 수집한 근거로 질문에 충분히 답할 수 있는가"를 평가해 LLM 의
 * 구조화 출력(JSON)으로 생성한다. 부족하면 무엇이 부족한지(missing)와 다음 행동(nextAction)을 함께 제시한다.
 *
 * @param sufficient 현재 근거로 질문에 충분·정확히 답할 수 있으면 true
 * @param missing    부족한 정보/근거에 대한 설명(sufficient=false 일 때)
 * @param nextAction 부족을 메우기 위해 다음에 수행할 구체적 행동(추가 검색어·다른 도구 등)
 */
@JsonClassDescription("수집된 근거로 질문에 충분히 답할 수 있는지에 대한 평가 결과")
public record EvaluationVerdict(
        @JsonPropertyDescription("현재 근거로 질문에 충분하고 정확하게 답할 수 있으면 true, 아니면 false")
        boolean sufficient,

        @JsonPropertyDescription("부족한 정보나 근거에 대한 간단한 설명. 충분하면 빈 문자열")
        String missing,

        @JsonPropertyDescription("부족을 메우기 위해 다음에 수행할 구체적 행동(추가 검색어 또는 다른 도구 사용). 충분하면 빈 문자열")
        String nextAction
) {

    /** 평가 실패 시 무한 루프를 막기 위한 안전 폴백(충분한 것으로 간주해 루프를 종료). */
    public static EvaluationVerdict sufficientFallback() {
        return new EvaluationVerdict(true, "", "");
    }
}
