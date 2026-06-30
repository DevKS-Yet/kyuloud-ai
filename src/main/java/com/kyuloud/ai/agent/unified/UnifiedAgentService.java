package com.kyuloud.ai.agent.unified;

import com.kyuloud.ai.common.ConversationHistory;
import com.kyuloud.ai.common.Conversations;
import com.kyuloud.ai.config.AgentBudgetProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Phase 6 — 통합 에이전트(Workflow-scaffolded)의 단일 진입점 오케스트레이션.
 *
 * <p>흐름: 대화 맥락 읽기 → {@link RouterService Router} 분류 → 전략별 {@link RouteHandler} 처리 → 응답.
 * 누적 구현(분산 엔드포인트, 메모리 seed/clear 트릭, ThreadLocal tracker)을 재활용하지 않고 클린 재구현한다:
 * 중간 산출물은 임시 conversationId 에 적재했다 지우지 않고 {@link AgentContext}(코드)가 보유하며,
 * 도구 추적은 {@link CallTracer}(수집형, {@code ToolContext} 주입)로 병렬·스트리밍에서도 안전하게 한다.
 *
 * <p><b>Phase 8c</b>: DIRECT/CLARIFY/RESEARCH 의 {@code if/else} 분기를 {@link RouteHandler} 전략 빈으로 분리하고
 * {@link RouteHandlerRegistry}(enum 키 자동수집)가 디스패치한다. 이 서비스는 컨텍스트 구성·라우팅·디스패치·
 * 폴백·관측만 맡는 얇은 오케스트레이터로 남는다(전략별 로직은 각 핸들러 소유, open-closed).
 *
 * <p>분류 결과(routed)와 실제 수행(executed)을 응답에 함께 노출한다 — CLARIFY 과민 시 DIRECT 로 폴백하므로
 * 둘이 다를 수 있다(routed=CLARIFY, executed=DIRECT).
 */
@Slf4j
@Service
public class UnifiedAgentService {

    private final RouterService routerService;
    private final ModelCatalog modelCatalog;
    private final ChatMemory chatMemory;
    private final AgentBudgetProperties budgetProperties;
    private final RouteHandlerRegistry routeHandlerRegistry;

    public UnifiedAgentService(RouterService routerService,
                               ModelCatalog modelCatalog,
                               ChatMemory chatMemory,
                               AgentBudgetProperties budgetProperties,
                               RouteHandlerRegistry routeHandlerRegistry) {
        this.routerService = routerService;
        this.modelCatalog = modelCatalog;
        this.chatMemory = chatMemory;
        this.budgetProperties = budgetProperties;
        this.routeHandlerRegistry = routeHandlerRegistry;
    }

    /**
     * 통합 에이전트 진입점. 대화 맥락을 읽어 Router 로 분류한 뒤 해당 {@link RouteHandler} 로 디스패치한다.
     * 메모리에는 각 핸들러가 '원 질문 → 최종 답변' 한 턴만 기록한다(중간 산출물은 {@link AgentContext} 가 보유).
     */
    public UnifiedAgentResponse agent(String conversationId, String message, String requestedModel) {
        String cid = Conversations.resolve(conversationId);
        // Phase 7 — allow-list 검증·해석(미지정 시 기본). 허용 안 된 모델이면 여기서 400.
        String model = modelCatalog.resolve(requestedModel);
        AgentContext ctx = new AgentContext(cid,
                new Budget(budgetProperties.getMaxLlmCalls(), budgetProperties.getTimeoutMillis()),
                new CallTracer(), model);
        log.debug("unified agent: cid={}, model={}, message={}", cid, model, message);

        List<Message> history = chatMemory.get(cid);
        String context = ConversationHistory.render(history);
        RouteDecision decision = routeWithBudget(ctx, message, context);

        RouteRequest request = new RouteRequest(message, context, history, decision.strategy());
        UnifiedAgentResponse response = routeHandlerRegistry.get(decision.strategy()).handle(ctx, request);
        if (response == null) {
            // CLARIFY 과민(되물을 게 없음) → DIRECT 폴백. 핸들러가 null 로 위임을 신호한다.
            log.debug("unified agent: CLARIFY → DIRECT 폴백");
            response = routeHandlerRegistry.direct().handle(ctx, request);
        }

        log.debug("unified agent done: routed={}, executed={}, model={}, llmCalls={}, evidence={}, tools={}",
                response.routed(), response.executed(), response.executedModel(),
                ctx.budget().usedLlmCalls(), ctx.evidence().size(), response.toolsUsed());
        return response;
    }

    /** Router 분류 1회를 예산에 반영하고 수행한다. */
    private RouteDecision routeWithBudget(AgentContext ctx, String message, String context) {
        ctx.budget().accountLlmCall("router");
        return routerService.route(message, context);
    }
}
