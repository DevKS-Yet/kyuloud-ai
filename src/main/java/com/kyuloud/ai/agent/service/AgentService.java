package com.kyuloud.ai.agent.service;

import com.kyuloud.ai.agent.dto.AgentResponse;
import com.kyuloud.ai.agent.dto.PlanResponse;
import com.kyuloud.ai.agent.dto.PlanStep;
import com.kyuloud.ai.agent.dto.StepResult;
import com.kyuloud.ai.agent.planner.Plan;
import com.kyuloud.ai.agent.planner.PlannerService;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
    private final PlannerService plannerService;
    private final ChatMemory chatMemory;

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
                        PlannerService plannerService,
                        ChatMemory chatMemory,
                        ObjectProvider<SyncMcpToolCallbackProvider> mcpProvider) {
        this.chatClient = chatClient;
        this.dateTimeTool = dateTimeTool;
        this.ragSearchTool = ragSearchTool;
        this.documentCatalogTool = documentCatalogTool;
        this.webSearchTool = webSearchTool;
        this.toolCallTracker = toolCallTracker;
        this.plannerService = plannerService;
        this.chatMemory = chatMemory;
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
        log.debug("agent chat: cid={}, message={}", cid, message);

        try {
            String reply = agentSpec(cid, message).call().content();
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
        log.debug("agent stream: cid={}, message={}", cid, message);
        return agentSpec(cid, message).stream().content();
    }

    /**
     * Phase 3d-4 — Plan-and-Execute.
     *
     * <p>복잡한 multi-step 요청을 {@link PlannerService} 로 <em>계획</em>(순서가 있는 단계)으로 분해한 뒤,
     * 각 단계를 도구 탑재 에이전트로 <em>순차 실행</em>하고, 마지막에 결과를 <em>합성</em>해 최종 답변을 만든다.
     * 단발 {@link #chat} 의 자동 ReAct 루프와 달리 계획·실행을 명시적으로 분리해 다단계 작업을 다룬다.
     *
     * <p>단계 실행은 사용자 대화({@code cid})와 분리된 임시 {@code planCid} 컨텍스트에서 수행한다.
     * 한 plan 안의 단계들은 이 임시 메모리를 통해 앞 단계 결과를 공유하고, 실행이 끝나면
     * {@link ChatMemory#clear} 로 정리해 사용자 대화 오염과 메모리 누수를 막는다.
     */
    public PlanResponse planAndExecute(String conversationId, String message) {
        String cid = StringUtils.hasText(conversationId) ? conversationId : DEFAULT_CONVERSATION_ID;
        String planCid = "plan-" + cid + "-" + UUID.randomUUID();
        log.debug("agent plan-and-execute: cid={}, planCid={}, message={}", cid, planCid, message);

        try {
            Plan plan = plannerService.plan(message);
            List<StepResult> stepResults = new ArrayList<>();

            for (PlanStep step : plan.steps()) {
                String stepReply = agentSpec(planCid, buildStepPrompt(message, step)).call().content();
                stepResults.add(new StepResult(step.order(), step.description(), stepReply));
            }

            // 단일 단계면 합성용 LLM 호출을 생략(불필요한 비용 방지)하고 그 단계 결과를 그대로 최종 답변으로 쓴다.
            String finalReply = stepResults.size() == 1
                    ? stepResults.get(0).result()
                    : plannerService.synthesize(message, stepResults);

            return new PlanResponse(message, plan.steps(), stepResults, finalReply,
                    toolCallTracker.getCalledTools());
        } finally {
            chatMemory.clear(planCid);
            toolCallTracker.reset();
        }
    }

    /**
     * 도구 + 메모리(conversationId) 를 결합한 에이전트 요청 스펙을 구성한다. blocking/스트리밍/단계 실행이 공유한다.
     * MCP 도구는 연결이 있을 때만 추가된다.
     */
    private ChatClient.ChatClientRequestSpec agentSpec(String cid, String userMessage) {
        ToolCallback[] mcp = mcpToolCallbacks();
        var spec = chatClient.prompt()
                .system(agentSystemPrompt)
                .user(userMessage)
                .tools(dateTimeTool, ragSearchTool, documentCatalogTool, webSearchTool)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, cid));
        if (mcp.length > 0) {
            spec = spec.tools((Object[]) mcp);
        }
        return spec;
    }

    /**
     * 단계 실행용 프롬프트. 전체 요청 맥락과 "지금 수행할 단계"를 함께 주어 한 단계씩 처리하게 한다.
     * 앞 단계 결과는 {@code planCid} 대화 메모리로 전달되므로 프롬프트에 중복 포함하지 않는다.
     */
    private String buildStepPrompt(String originalRequest, PlanStep step) {
        return "전체 요청: " + originalRequest + "\n\n"
                + "지금 수행할 단계(" + step.order() + "): " + step.description() + "\n"
                + "이 단계만 수행하고 그 결과를 답하세요. 앞 단계의 결과는 대화 맥락에 있으니 참고하세요. "
                + "필요하면 도구를 호출하세요.";
    }
}
