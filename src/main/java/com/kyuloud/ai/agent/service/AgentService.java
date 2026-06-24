package com.kyuloud.ai.agent.service;

import com.kyuloud.ai.agent.dto.AgentResponse;
import com.kyuloud.ai.agent.tool.DateTimeTool;
import com.kyuloud.ai.agent.tool.DocumentCatalogTool;
import com.kyuloud.ai.agent.tool.RagSearchTool;
import com.kyuloud.ai.agent.tool.WebSearchTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

/**
 * Phase 3a — Agent 채팅 서비스.
 *
 * <p>기존 {@link ChatClient}(대화 메모리 advisor 포함)를 재사용하고, 요청별로 도구를
 * 주입한다(전역 {@code defaultTools}를 쓰지 않아 chat/rag 경로에는 영향을 주지 않음).
 * tool-calling 의 ReAct 루프(추론→도구호출→관찰→반복)는 Spring AI 가 자동 처리한다.
 *
 * <p>Phase 3d-3: {@link SyncMcpToolCallbackProvider} 를 {@link ObjectProvider} 로 주입해
 * {@code spring.ai.mcp.client.enabled=false} 이거나 IntelliJ 가 미실행 상태일 때도
 * 앱이 정상 기동된다. MCP 연결이 있을 때만 IDE 도구가 활성화된다.
 */
@Slf4j
@Service
public class AgentService {

    private static final String DEFAULT_CONVERSATION_ID = "default";

    private final ChatClient chatClient;
    private final DateTimeTool dateTimeTool;
    private final RagSearchTool ragSearchTool;
    private final DocumentCatalogTool documentCatalogTool;
    private final WebSearchTool webSearchTool;
    private final ToolCallTracker toolCallTracker;

    /**
     * MCP 도구 공급자(optional). 생성자에서 즉시 해석하지 않고 {@link ObjectProvider} 로 보관해,
     * MCP 미설정/연결 실패가 {@code AgentService} 빈 생성을 깨뜨리지 않게 한다.
     */
    private final ObjectProvider<SyncMcpToolCallbackProvider> mcpProvider;

    /** 최초 1회 lazy 해석한 MCP 도구 캐시(해석 실패 시 빈 배열). */
    private volatile ToolCallback[] mcpToolCallbacks;
    private volatile boolean mcpResolved;

    @Value("classpath:prompts/system-agent.st")
    private Resource agentSystemPrompt;

    public AgentService(ChatClient chatClient,
                        DateTimeTool dateTimeTool,
                        RagSearchTool ragSearchTool,
                        DocumentCatalogTool documentCatalogTool,
                        WebSearchTool webSearchTool,
                        ToolCallTracker toolCallTracker,
                        ObjectProvider<SyncMcpToolCallbackProvider> mcpProvider) {
        this.chatClient = chatClient;
        this.dateTimeTool = dateTimeTool;
        this.ragSearchTool = ragSearchTool;
        this.documentCatalogTool = documentCatalogTool;
        this.webSearchTool = webSearchTool;
        this.toolCallTracker = toolCallTracker;
        this.mcpProvider = mcpProvider;
    }

    /**
     * MCP 도구를 lazy 하게 1회 해석한다. MCP 가 비활성(빈 없음)이거나 연결/조회에 실패하면
     * 빈 배열을 반환하고 캐시해, 이후 요청과 앱 기동에 영향을 주지 않는다(graceful degrade).
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

    public AgentResponse chat(String conversationId, String message) {
        String cid = StringUtils.hasText(conversationId) ? conversationId : DEFAULT_CONVERSATION_ID;
        ToolCallback[] mcp = mcpToolCallbacks();
        log.debug("agent chat: cid={}, message={}, mcpTools={}", cid, message, mcp.length);

        try {
            var spec = chatClient.prompt()
                    .system(agentSystemPrompt)
                    .user(message)
                    .tools(dateTimeTool, ragSearchTool, documentCatalogTool, webSearchTool)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, cid));

            if (mcp.length > 0) {
                spec = spec.tools((Object[]) mcp);
            }

            String reply = spec.call().content();
            return new AgentResponse(reply, toolCallTracker.getCalledTools());
        } finally {
            toolCallTracker.reset();
        }
    }

    /**
     * Phase 3d-1 — 스트리밍 tool-calling.
     *
     * <p>도구 호출(ReAct 루프)을 수행하면서 최종 답변을 토큰 단위 SSE 로 스트리밍한다.
     */
    public Flux<String> stream(String conversationId, String message) {
        String cid = StringUtils.hasText(conversationId) ? conversationId : DEFAULT_CONVERSATION_ID;
        ToolCallback[] mcp = mcpToolCallbacks();
        log.debug("agent stream: cid={}, message={}, mcpTools={}", cid, message, mcp.length);

        var spec = chatClient.prompt()
                .system(agentSystemPrompt)
                .user(message)
                .tools(dateTimeTool, ragSearchTool, documentCatalogTool, webSearchTool)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, cid));

        if (mcp.length > 0) {
            spec = spec.tools((Object[]) mcp);
        }

        return spec.stream().content();
    }
}
