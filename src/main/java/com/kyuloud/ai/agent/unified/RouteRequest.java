package com.kyuloud.ai.agent.unified;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * Phase 8c — 라우트 핸들러 입력. Router 분류 시점에 확정된 요청 정보를 묶는다(요청 단위 <em>가변</em> 상태는
 * {@link AgentContext} 가 보유 — budget·tracer·evidence). 핸들러 시그니처를 좁게 유지하려고 분리했다.
 *
 * @param message     원본 사용자 질문
 * @param contextText 대화 기록을 역할 라벨로 렌더링한 텍스트(명확화·분해 입력용)
 * @param history     원본 대화 기록(DIRECT 가 메시지로 직접 주입)
 * @param routed      Router 가 고른 전략. 응답의 {@code routed} 필드로 노출하며, CLARIFY→DIRECT 폴백 시
 *                    {@code executed} 와 달라진다(이때 routed=CLARIFY, executed=DIRECT)
 */
public record RouteRequest(
        String message,
        String contextText,
        List<Message> history,
        RouteStrategy routed
) {
}
