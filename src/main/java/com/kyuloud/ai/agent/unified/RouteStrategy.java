package com.kyuloud.ai.agent.unified;

/**
 * Phase 6 — 통합 에이전트 Router 가 선택하는 처리 전략(Routing 패턴).
 *
 * <p>약한 로컬 모델이 한 번에 좁은 판단 하나(= 전략 선택)만 하도록 후보를 셋으로 제한한다.
 *
 * <ul>
 *   <li>{@link #DIRECT} — 단발로 답할 수 있는 질문. 필요 시 문서 검색을 1회 곁들여 바로 답한다.</li>
 *   <li>{@link #RESEARCH} — 여러 하위작업으로 나눠 조사·종합해야 하는 다단계 질문(Orchestrator-workers).</li>
 *   <li>{@link #CLARIFY} — 핵심 정보가 부족해 전략조차 고를 수 없어 사용자에게 되물어야 하는 질문.</li>
 * </ul>
 */
public enum RouteStrategy {
    DIRECT,
    RESEARCH,
    CLARIFY
}
