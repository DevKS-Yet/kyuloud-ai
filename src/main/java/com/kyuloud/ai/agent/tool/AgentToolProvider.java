package com.kyuloud.ai.agent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@link ToolProvider} 기본 구현 — 공용 도구({@link AgentTool} 마커 빈)와 MCP 도구를 한데 모은다.
 *
 * <p>그동안 각 서비스가 도구 빈 4개 + {@code mcpProvider} 를 따로 주입하고 MCP lazy 해석 코드를 <em>똑같이
 * 세 벌</em> 들고 있었는데, 그 책임을 이 한 빈으로 모은다. 호출부는 {@code toolProvider.tools()} 한 번이면 된다.
 *
 * <p>MCP 도구는 생성자에서 즉시 해석하지 않고 {@link ObjectProvider} 로 보관했다가 최초 1회만 lazy 해석하고
 * 캐시한다. {@code spring.ai.mcp.client.enabled=false} 이거나 연결/조회에 실패해도 빈 배열로 degrade 해
 * 빈 생성·앱 기동·이후 요청에 영향을 주지 않는다(graceful degrade).
 */
@Slf4j
@Component
public class AgentToolProvider implements ToolProvider {

    /** {@link AgentTool} 를 구현한 모든 도구 빈(Spring 이 자동 수집). 새 도구는 마커만 달면 여기에 합류한다. */
    private final List<AgentTool> agentTools;
    private final ObjectProvider<SyncMcpToolCallbackProvider> mcpProvider;

    /** 최초 1회 lazy 해석한 MCP 도구 캐시(해석 실패 시 빈 배열). */
    private volatile ToolCallback[] mcpToolCallbacks;
    private volatile boolean mcpResolved;

    public AgentToolProvider(List<AgentTool> agentTools,
                             ObjectProvider<SyncMcpToolCallbackProvider> mcpProvider) {
        this.agentTools = agentTools;
        this.mcpProvider = mcpProvider;
        log.info("AgentToolProvider: 공용 도구 {}개 등록", agentTools.size());
    }

    @Override
    public Object[] tools(Object... additionalTools) {
        ToolCallback[] mcp = mcpToolCallbacks();
        List<Object> all = new ArrayList<>(agentTools.size() + additionalTools.length + mcp.length);
        all.addAll(agentTools);
        if (additionalTools.length > 0) {
            Collections.addAll(all, additionalTools);
        }
        Collections.addAll(all, (Object[]) mcp);
        return all.toArray();
    }

    /**
     * MCP 도구를 lazy 하게 1회 해석한다. MCP 가 비활성(빈 없음)이거나 연결/조회에 실패하면 빈 배열을 반환·캐시해
     * 이후 요청과 앱 기동에 영향을 주지 않는다(graceful degrade).
     */
    private ToolCallback[] mcpToolCallbacks() {
        if (mcpResolved) {
            return mcpToolCallbacks;
        }
        synchronized (this) {
            if (!mcpResolved) {
                ToolCallback[] resolved = new ToolCallback[0];
                try {
                    SyncMcpToolCallbackProvider provider = mcpProvider.getIfAvailable();
                    if (provider != null) {
                        resolved = provider.getToolCallbacks();
                        log.info("MCP 도구 {}개 로드됨", resolved.length);
                    }
                } catch (Exception e) {
                    log.warn("MCP 도구 로드 실패 — MCP 없이 진행합니다: {}", e.getMessage());
                }
                this.mcpToolCallbacks = resolved;
                this.mcpResolved = true;
            }
        }
        return mcpToolCallbacks;
    }
}
