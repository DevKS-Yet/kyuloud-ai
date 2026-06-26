package com.kyuloud.ai.agent.unified;

import com.kyuloud.ai.agent.clarify.ClarificationService;
import com.kyuloud.ai.agent.clarify.ClarificationVerdict;
import com.kyuloud.ai.agent.dto.ClarifyingQuestion;
import com.kyuloud.ai.agent.tool.ToolProvider;
import com.kyuloud.ai.config.AgentBudgetProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Phase 6 — 통합 에이전트(Workflow-scaffolded)의 단일 진입점 오케스트레이션.
 *
 * <p>흐름: 대화 맥락 읽기 → {@link RouterService Router} 분류 → 전략별 처리 → 최종 한 턴만 메모리에 기록.
 * 누적 구현(분산 엔드포인트, 메모리 seed/clear 트릭, ThreadLocal tracker)을 재활용하지 않고 클린 재구현한다:
 * 중간 산출물은 임시 conversationId 에 적재했다 지우지 않고 {@link AgentContext}(코드)가 보유하며,
 * 도구 추적은 {@link CallTracer}(수집형, {@code ToolContext} 주입)로 병렬·스트리밍에서도 안전하게 한다.
 *
 * <p><b>범위</b>: 6a — DIRECT 경로, 6b — CLARIFY 분기, 6c — RESEARCH(Orchestrator-workers 순차)를
 * {@link OrchestratorService} 에 위임. 분류 결과(routed)와 실제 수행(executed)을 응답에 함께 노출한다
 * (CLARIFY 과민 시 DIRECT 로 폴백하므로 둘이 다를 수 있다).
 */
@Slf4j
@Service
public class UnifiedAgentService {

    private static final String DEFAULT_CONVERSATION_ID = "default";

    private final RouterService routerService;
    private final ClarificationService clarificationService;
    private final OrchestratorService orchestratorService;
    private final KnowledgeRetriever knowledgeRetriever;
    private final ChatClient workerChatClient;
    private final ChatMemory chatMemory;
    private final AgentBudgetProperties budgetProperties;
    private final ToolProvider toolProvider;

    @Value("classpath:prompts/system-direct.st")
    private Resource directSystemPrompt;

    public UnifiedAgentService(RouterService routerService,
                               ClarificationService clarificationService,
                               OrchestratorService orchestratorService,
                               KnowledgeRetriever knowledgeRetriever,
                               @Qualifier("workerChatClient") ChatClient workerChatClient,
                               ChatMemory chatMemory,
                               AgentBudgetProperties budgetProperties,
                               ToolProvider toolProvider) {
        this.routerService = routerService;
        this.clarificationService = clarificationService;
        this.orchestratorService = orchestratorService;
        this.knowledgeRetriever = knowledgeRetriever;
        this.workerChatClient = workerChatClient;
        this.chatMemory = chatMemory;
        this.budgetProperties = budgetProperties;
        this.toolProvider = toolProvider;
    }

    /**
     * 통합 에이전트 진입점. 대화 맥락을 읽어 Router 로 분류한 뒤 전략별로 처리하고, 사용자 대화에는
     * 원 질문 → 최종 답변 한 턴만 기록한다(중간 산출물은 {@link AgentContext} 가 보유).
     */
    public UnifiedAgentResponse agent(String conversationId, String message) {
        String cid = StringUtils.hasText(conversationId) ? conversationId : DEFAULT_CONVERSATION_ID;
        AgentContext ctx = new AgentContext(cid,
                new Budget(budgetProperties.getMaxLlmCalls(), budgetProperties.getTimeoutMillis()),
                new CallTracer());
        log.debug("unified agent: cid={}, message={}", cid, message);

        List<Message> history = chatMemory.get(cid);
        String context = renderHistory(history);
        RouteDecision decision = routeWithBudget(ctx, message, context);

        // CLARIFY 분기(6b): Router 가 CLARIFY 면 명확화 전문가로 되묻기를 생성한다. 전문가가 되물을 게 없다고
        // 판단하면(Router 과민) DIRECT 로 진행한다.
        if (decision.strategy() == RouteStrategy.CLARIFY) {
            UnifiedAgentResponse clarify = tryClarify(ctx, cid, message, context);
            if (clarify != null) {
                return clarify;
            }
        }

        // RESEARCH 분기(6c): 다단계 질문은 Orchestrator 가 워커 하위작업으로 분해→순차 실행→근거 누적→합성.
        // 그 외(또는 CLARIFY 과민 폴백)는 DIRECT 단발 답변.
        RouteStrategy executed;
        String reply;
        if (decision.strategy() == RouteStrategy.RESEARCH) {
            executed = RouteStrategy.RESEARCH;
            reply = orchestratorService.research(ctx, message, context);
        } else {
            executed = RouteStrategy.DIRECT;
            reply = direct(ctx, history, message);
        }

        recordTurn(cid, message, reply);
        log.debug("unified agent done: routed={}, executed={}, llmCalls={}, evidence={}, tools={}",
                decision.strategy(), executed, ctx.budget().usedLlmCalls(),
                ctx.evidence().size(), ctx.tracer().getCalledTools());
        return new UnifiedAgentResponse(message, decision.strategy(), executed, reply,
                List.of(), ctx.evidence(), ctx.tracer().getCalledTools());
    }

    /**
     * CLARIFY 경로(6b) — 답변하지 않고, 제대로 답하기 위해 사용자에게 되물을 핵심 정보를 질문+선택지로 만든다.
     * 대화 맥락을 반영해 이미 아는 정보는 묻지 않는다. 되물을 게 있으면 그 턴(원 질문 → 되묻기)을 메모리에 기록해,
     * 사용자가 같은 {@code conversationId} 로 답을 더해 재호출하면 맥락이 이어지게 한다(연속성, #5).
     *
     * <p>명확화 전문가가 되물을 게 없다고 판단하면(Router 의 CLARIFY 가 과했던 경우) {@code null} 을 반환해
     * 호출부가 DIRECT 로 진행하게 한다.
     */
    private UnifiedAgentResponse tryClarify(AgentContext ctx, String cid, String message, String context) {
        accountLlmCall(ctx, "clarify");
        ClarificationVerdict verdict = clarificationService.assess(message, context);
        List<ClarifyingQuestion> questions = verdict.questions();
        if (!verdict.needsClarification() || questions == null || questions.isEmpty()) {
            log.debug("unified agent: Router=CLARIFY 였으나 되물을 정보 없음 → DIRECT 진행");
            return null;
        }

        String rendered = renderClarification(questions);
        // 연속성(#5): 되묻기를 한 턴으로 기록해 재호출 시 맥락 유지. (DIRECT 의 recordTurn 과 동일한 자리)
        recordTurn(cid, message, rendered);
        log.debug("unified agent done: routed=CLARIFY, executed=CLARIFY, questions={}", questions.size());
        return new UnifiedAgentResponse(message, RouteStrategy.CLARIFY, RouteStrategy.CLARIFY,
                rendered, questions, List.of(), ctx.tracer().getCalledTools());
    }

    /** 되묻는 질문 목록을 사람이 읽을 수 있는 텍스트로 렌더링한다(응답 reply + 메모리 기록용). */
    private String renderClarification(List<ClarifyingQuestion> questions) {
        StringBuilder sb = new StringBuilder("정확히 답하려면 몇 가지 확인이 필요합니다:");
        for (ClarifyingQuestion q : questions) {
            sb.append("\n- ").append(q.question());
            if (q.options() != null && !q.options().isEmpty()) {
                sb.append(" (선택: ").append(String.join(" / ", q.options())).append(")");
            }
        }
        return sb.toString();
    }

    /** Router 분류 1회를 예산에 반영하고 수행한다. */
    private RouteDecision routeWithBudget(AgentContext ctx, String message, String context) {
        accountLlmCall(ctx, "router");
        return routerService.route(message, context);
    }

    /**
     * DIRECT 경로 — 항상 1회 문서 검색(threshold 필터)으로 컨텍스트를 곁들이고, 도구를 갖춘 단발 답변을 만든다.
     * 대화 맥락은 메모리 advisor 가 아니라 읽어온 {@code history} 를 직접 메시지로 주입해 명시적으로 다룬다.
     * 도구 추적은 {@code ToolContext} 에 실은 {@link CallTracer} 로 수집한다(#3 PoC).
     */
    private String direct(AgentContext ctx, List<Message> history, String message) {
        String docContext = knowledgeRetriever.retrieveContext(message);
        String userMessage = StringUtils.hasText(docContext)
                ? "참고 문서:\n" + docContext + "\n\n위 문서가 질문과 관련 있으면 근거로 삼아 답하세요(출처 표기). "
                        + "관련 없으면 무시하세요.\n\n질문:\n" + message
                : message;

        accountLlmCall(ctx, "direct");
        return workerChatClient.prompt()
                .system(directSystemPrompt)
                .messages(history)
                .user(userMessage)
                .tools(toolProvider.tools())
                .toolContext(ctx.tracer().asToolContext())
                .call()
                .content();
    }

    /**
     * LLM 호출 1회를 예산에 반영한다. 6a 에서는 단발 경로라 초과가 발생하지 않지만, 정지조건 메커니즘을
     * 지금부터 작동시킨다(초과해도 막지 않고 경고만; 실제 반복 차단은 6c+ 의 루프에서 {@code isExhausted()} 로 한다).
     */
    private void accountLlmCall(AgentContext ctx, String purpose) {
        if (!ctx.budget().tryConsumeLlmCall()) {
            log.warn("unified agent: 예산 초과 상태에서 LLM 호출({}) — best-effort 진행 (used={}/{})",
                    purpose, ctx.budget().usedLlmCalls(), ctx.budget().maxLlmCalls());
        }
    }

    /** 사용자 대화({@code cid})에 '원 질문 → 최종 답변' 한 턴만 기록한다(중간 산출물 제외). */
    private void recordTurn(String cid, String userMessage, String reply) {
        chatMemory.add(cid, List.of(new UserMessage(userMessage), new AssistantMessage(reply)));
    }

    /** 대화 기록을 Router 분류용 컨텍스트 텍스트(역할 라벨 + 내용)로 렌더링한다. */
    private String renderHistory(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Message m : history) {
            sb.append(roleLabel(m.getMessageType())).append(": ").append(m.getText()).append("\n");
        }
        return sb.toString();
    }

    private String roleLabel(MessageType type) {
        return switch (type) {
            case USER -> "사용자";
            case ASSISTANT -> "어시스턴트";
            case SYSTEM -> "시스템";
            default -> type.getValue();
        };
    }
}
