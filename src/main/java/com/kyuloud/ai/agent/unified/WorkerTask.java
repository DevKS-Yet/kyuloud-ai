package com.kyuloud.ai.agent.unified;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Phase 6c — Orchestrator 가 RESEARCH 질문을 동적 분해해 만든 하나의 워커 하위작업.
 *
 * <p>각 하위작업은 도구를 갖춘 리서처 워커가 <em>순차</em> 수행하고 근거만 보고한다(최종 답변은 합성 단계에서).
 * 구조화 출력(JSON)으로 LLM 이 직접 생성하므로 필드 설명을 함께 제공해 스키마 품질을 높인다.
 *
 * @param order     수행 순서(1부터 시작). 뒤 작업이 앞 작업의 결과에 의존할 수 있어 오름차순으로 처리한다.
 * @param objective 이 워커가 알아내야 할 단일 조사 목표(무엇을 찾을지, 필요하면 어떤 도구를 쓸지 자연어로)
 */
public record WorkerTask(
        @JsonPropertyDescription("수행 순서, 1부터 시작하는 정수")
        int order,

        @JsonPropertyDescription("이 워커가 알아내야 할 단일 조사 목표. 도구(날짜/시각·문서 검색·문서 목록·웹 검색)로 "
                + "달성 가능한 구체적 조사 작업으로 적는다.")
        String objective
) {
}
