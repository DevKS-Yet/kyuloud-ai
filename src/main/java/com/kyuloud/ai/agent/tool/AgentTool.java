package com.kyuloud.ai.agent.tool;

/**
 * 에이전트 공용 도구 마커. {@code @Tool} 메서드를 가진 도구 빈이 이 인터페이스를 구현하면
 * {@link ToolProvider} 가 {@code List<AgentTool>} 로 <em>자동 수집</em>해 모든 에이전트 경로에 노출한다.
 *
 * <p>새 도구를 추가할 때 이 마커만 구현하면 별도의 주입·배선 없이 {@code /api/agent}(DIRECT·워커)와 구
 * {@code /api/agent/chat} 등 모든 tool-calling 경로에서 즉시 사용 가능하다(개방-폐쇄: 호출부 수정 불필요).
 *
 * <p><b>주의</b>: RAG 검색({@code RagSearchTool})은 의도적으로 이 마커를 달지 <em>않는다</em>. 통합 흐름은
 * RAG 를 도구로 노출하지 않고 {@code KnowledgeRetriever} 로 직접 1회 검색하기로 단일화했기 때문(#1=c).
 * 그 도구가 필요한 (구) 경로는 {@link ToolProvider#tools(Object...)} 의 추가 인자로 명시적으로 넘긴다.
 */
public interface AgentTool {
}
