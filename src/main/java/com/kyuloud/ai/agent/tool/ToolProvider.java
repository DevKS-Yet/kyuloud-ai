package com.kyuloud.ai.agent.tool;

/**
 * 에이전트 도구 공급자. 개별 도구 빈과 MCP 도구를 일일이 주입·배선하는 대신, 이 한 빈만 주입하면 모든
 * tool-calling 도구를 한 번에 받을 수 있다({@code mcpProvider} 를 인터페이스화한 것과 같은 발상).
 *
 * <p>반환 배열은 그대로 Spring AI 의 {@code ChatClient...tools(Object...)} 에 펼쳐 넘기면 된다 —
 * 그 메서드는 {@code @Tool} 빈과 {@code ToolCallback}(MCP) 를 함께 받는다.
 */
public interface ToolProvider {

    /**
     * 에이전트가 쓸 도구 묶음을 반환한다: {@link AgentTool} 마커로 자동 수집된 공용 도구 + (연결돼 있으면) MCP
     * 도구 콜백, 그리고 호출부가 넘긴 추가 도구. MCP 미설정·연결 실패는 graceful degrade 로 조용히 생략된다.
     *
     * @param additionalTools 이 호출에만 더 붙일 도구 빈({@code @Tool}). 공용이 아닌 경로 한정 도구를 넘길 때 사용
     *                        (예: 구 경로의 {@code RagSearchTool}). 없으면 비워 호출한다.
     * @return {@code ChatClient...tools(Object...)} 에 그대로 펼쳐 넘길 도구/콜백 배열
     */
    Object[] tools(Object... additionalTools);
}
