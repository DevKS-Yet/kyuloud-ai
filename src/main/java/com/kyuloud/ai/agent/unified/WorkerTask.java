package com.kyuloud.ai.agent.unified;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Phase 6c — Orchestrator 가 RESEARCH 질문을 동적 분해해 만든 하나의 워커 하위작업.
 *
 * <p>각 하위작업은 도구를 갖춘 리서처 워커가 수행하고 근거만 보고한다(최종 답변은 합성 단계에서). 서로 독립인
 * 작업은 가상스레드로 <em>병렬</em> 실행하고, 앞선 작업 결과에 의존하는 작업({@code dependsOnPrevious})은
 * 그 전까지를 모두 마친 뒤 <em>순차</em> 실행한다(6d). 구조화 출력(JSON)으로 LLM 이 직접 생성하므로 필드
 * 설명을 함께 제공해 스키마 품질을 높인다.
 *
 * @param order             수행 순서(1부터 시작, 오름차순 처리).
 * @param objective         이 워커가 알아내야 할 단일 조사 목표(무엇을 찾을지, 필요하면 어떤 도구를 쓸지 자연어로)
 * @param dependsOnPrevious 앞선 하위작업들의 결과가 있어야 수행 가능하면 {@code true}(순차 배리어), 독립적으로
 *                          수행 가능하면 {@code false}(병렬 가능)
 */
public record WorkerTask(
        @JsonPropertyDescription("수행 순서, 1부터 시작하는 정수")
        int order,

        @JsonPropertyDescription("이 워커가 알아내야 할 단일 조사 목표. 도구(날짜/시각·문서 검색·문서 목록·웹 검색)로 "
                + "달성 가능한 구체적 조사 작업으로 적는다.")
        String objective,

        @JsonPropertyDescription("앞선 하위작업들의 결과가 있어야 수행할 수 있으면 true, 다른 작업과 무관하게 "
                + "독립적으로 수행 가능하면 false. 독립 작업(false)들은 동시에 병렬로 실행된다.")
        boolean dependsOnPrevious
) {
}
