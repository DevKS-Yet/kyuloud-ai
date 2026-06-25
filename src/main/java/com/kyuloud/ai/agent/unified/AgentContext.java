package com.kyuloud.ai.agent.unified;

import com.kyuloud.ai.agent.dto.StepResult;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Phase 6 — 통합 에이전트 요청 단위 컨텍스트. 코드가 명시적으로 들고 다니며 흐름 전체에서 공유한다.
 *
 * <p>누적 구현의 "임시 conversationId 에 기록했다 지우는" 메모리 seed/clear 트릭을 폐기하고, 중간 산출물을
 * DB 가 아닌 이 객체에 담는다:
 * <ul>
 *   <li>{@code conversationId} — 사용자 대화 세션(최종 한 턴만 실제 메모리에 기록).</li>
 *   <li>{@code budget} — LLM/시간 상한(정지조건).</li>
 *   <li>{@code tracer} — 요청별 수집형 도구 추적기(병렬·스트리밍 안전).</li>
 *   <li>{@code evidence} — RESEARCH 경로 워커들의 중간 근거(6c+ 에서 누적, synthesize 입력).</li>
 * </ul>
 */
public class AgentContext {

    private final String conversationId;
    private final Budget budget;
    private final CallTracer tracer;
    private final List<StepResult> evidence = new CopyOnWriteArrayList<>();

    public AgentContext(String conversationId, Budget budget, CallTracer tracer) {
        this.conversationId = conversationId;
        this.budget = budget;
        this.tracer = tracer;
    }

    public String conversationId() {
        return conversationId;
    }

    public Budget budget() {
        return budget;
    }

    public CallTracer tracer() {
        return tracer;
    }

    public void addEvidence(StepResult result) {
        evidence.add(result);
    }

    public List<StepResult> evidence() {
        return List.copyOf(evidence);
    }
}
