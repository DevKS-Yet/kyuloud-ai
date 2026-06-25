package com.kyuloud.ai.agent.unified;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * Phase 6c — Orchestrator 가 RESEARCH 질문을 분해한 워커 하위작업 묶음(구조화 출력 대상).
 *
 * <p>중앙 LLM(orchestrator)이 질문을 조사 가능한 최소한의 하위작업으로 나눈다. 각 {@link WorkerTask} 는
 * 도구를 갖춘 리서처 워커가 순차 수행한다. 단순하면 작업 1개로 충분하다(불필요한 분해 금지).
 *
 * @param tasks 순서가 있는 워커 하위작업 목록(order 오름차순)
 */
@JsonClassDescription("다단계 질문을 조사하기 위한 순서가 있는 워커 하위작업 묶음")
public record WorkerPlan(
        @JsonPropertyDescription("순서대로 수행할 워커 하위작업 목록. 한 번의 조사로 끝나면 작업 1개로 충분하다(최대 4개).")
        List<WorkerTask> tasks
) {
}
