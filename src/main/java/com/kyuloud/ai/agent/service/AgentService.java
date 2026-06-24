package com.kyuloud.ai.agent.service;

import com.kyuloud.ai.agent.dto.AgentResponse;
import com.kyuloud.ai.agent.tool.DateTimeTool;
import com.kyuloud.ai.agent.tool.DocumentCatalogTool;
import com.kyuloud.ai.agent.tool.RagSearchTool;
import com.kyuloud.ai.agent.tool.WebSearchTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
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

    @Value("classpath:prompts/system-agent.st")
    private Resource agentSystemPrompt;

    public AgentService(ChatClient chatClient,
                        DateTimeTool dateTimeTool,
                        RagSearchTool ragSearchTool,
                        DocumentCatalogTool documentCatalogTool,
                        WebSearchTool webSearchTool,
                        ToolCallTracker toolCallTracker) {
        this.chatClient = chatClient;
        this.dateTimeTool = dateTimeTool;
        this.ragSearchTool = ragSearchTool;
        this.documentCatalogTool = documentCatalogTool;
        this.webSearchTool = webSearchTool;
        this.toolCallTracker = toolCallTracker;
    }

    public AgentResponse chat(String conversationId, String message) {
        String cid = StringUtils.hasText(conversationId) ? conversationId : DEFAULT_CONVERSATION_ID;
        log.debug("agent chat: cid={}, message={}", cid, message);

        try {
            String reply = chatClient.prompt()
                    .system(agentSystemPrompt)
                    .user(message)
                    .tools(dateTimeTool, ragSearchTool, documentCatalogTool, webSearchTool)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, cid))
                    .call()
                    .content();

            return new AgentResponse(reply, toolCallTracker.getCalledTools());
        } finally {
            // blocking 경로는 .call()·도구 실행이 동일 스레드라 추적이 정확하다. 응답 반환 후
            // 스레드 로컬을 정리해 풀링 스레드 재사용 시 누수를 방지한다.
            toolCallTracker.reset();
        }
    }

    /**
     * Phase 3d-1 — 스트리밍 tool-calling.
     *
     * <p>도구 호출(ReAct 루프)을 수행하면서 최종 답변을 토큰 단위 SSE 로 스트리밍한다.
     * 스트리밍은 도구 실행이 reactor 스레드에서 일어날 수 있어 {@code toolsUsed} trace 는
     * 응답 본문에 싣지 않는다(필요 시 서버 로그/별도 메타 채널로 확인). 추적기는 ThreadLocal
     * 기반이라 요청 스레드 밖 호출에도 예외가 발생하지 않는다.
     */
    public Flux<String> stream(String conversationId, String message) {
        String cid = StringUtils.hasText(conversationId) ? conversationId : DEFAULT_CONVERSATION_ID;
        log.debug("agent stream: cid={}, message={}", cid, message);

        return chatClient.prompt()
                .system(agentSystemPrompt)
                .user(message)
                .tools(dateTimeTool, ragSearchTool, documentCatalogTool, webSearchTool)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, cid))
                .stream()
                .content();
    }
}
