package com.kyuloud.ai.agent.unified;

/**
 * Phase 8c — 라우트 처리 전략(Strategy 패턴). Router 가 고른 {@link RouteStrategy} 하나를 실제로 수행하는 단위다.
 *
 * <p>각 구현은 자신이 담당하는 전략을 {@link #strategy()} 로 선언하고, {@link RouteHandlerRegistry} 가 enum 키로
 * 자동 색인한다(빈 추가만으로 새 라우트가 등록되는 open-closed 구조). 기존 {@code UnifiedAgentService} 의
 * {@code if(CLARIFY)…/if(RESEARCH)…/else direct()} 분기를 전략별 핸들러로 분리한 것이다.
 */
public interface RouteHandler {

    /** 이 핸들러가 담당하는 전략(레지스트리 색인 키). */
    RouteStrategy strategy();

    /**
     * 라우트를 수행하고 최종 응답을 만든다(메모리 기록 포함).
     *
     * <p><b>폴백 신호</b>: CLARIFY 핸들러가 "되물을 게 없다"(Router 과민)고 판단하면 {@code null} 을 반환해
     * 호출부({@code UnifiedAgentService})가 DIRECT 핸들러로 위임하게 한다. DIRECT/RESEARCH 는 항상 응답을 반환한다.
     *
     * @param ctx     요청 단위 컨텍스트(budget·tracer·evidence·model 공유)
     * @param request Router 분류 시점에 확정된 요청 정보(질문·맥락·기록·routed)
     * @return 최종 응답, 또는 DIRECT 위임이 필요하면 {@code null}(CLARIFY 한정)
     */
    UnifiedAgentResponse handle(AgentContext ctx, RouteRequest request);
}
