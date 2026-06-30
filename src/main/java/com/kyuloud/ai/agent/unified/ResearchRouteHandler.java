package com.kyuloud.ai.agent.unified;

import com.kyuloud.ai.common.ChatMemories;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Phase 8c — RESEARCH 라우트 핸들러. 다단계 질문을 {@link OrchestratorService} 에 위임한다
 * (워커 하위작업 분해 → 병렬·순차 실행 → 근거 누적 → 합성). 누적 근거는 {@code ctx.evidence()} 에서 응답으로
 * 함께 노출해 추론 과정을 투명하게 보여준다. routed=executed=RESEARCH(폴백 목적지가 아님).
 */
@Slf4j
@Component
public class ResearchRouteHandler implements RouteHandler {

    private final OrchestratorService orchestratorService;
    private final ChatMemory chatMemory;

    public ResearchRouteHandler(OrchestratorService orchestratorService, ChatMemory chatMemory) {
        this.orchestratorService = orchestratorService;
        this.chatMemory = chatMemory;
    }

    @Override
    public RouteStrategy strategy() {
        return RouteStrategy.RESEARCH;
    }

    @Override
    public UnifiedAgentResponse handle(AgentContext ctx, RouteRequest request) {
        String reply = orchestratorService.research(ctx, request.message(), request.contextText());
        ChatMemories.recordTurn(chatMemory, ctx.conversationId(), request.message(), reply);
        return new UnifiedAgentResponse(request.message(), request.routed(), RouteStrategy.RESEARCH,
                reply, List.of(), ctx.evidence(), ctx.tracer().getCalledTools(), ctx.model());
    }
}
