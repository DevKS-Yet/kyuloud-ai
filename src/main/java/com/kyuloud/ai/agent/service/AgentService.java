package com.kyuloud.ai.agent.service;

import com.kyuloud.ai.agent.clarify.ClarificationService;
import com.kyuloud.ai.agent.clarify.ClarificationVerdict;
import com.kyuloud.ai.agent.dto.AgentResponse;
import com.kyuloud.ai.agent.dto.ClarifyResponse;
import com.kyuloud.ai.agent.dto.IterationTrace;
import com.kyuloud.ai.agent.dto.LoopResponse;
import com.kyuloud.ai.agent.dto.PlanResponse;
import com.kyuloud.ai.agent.dto.PlanStep;
import com.kyuloud.ai.agent.dto.StepResult;
import com.kyuloud.ai.agent.eval.EvaluationVerdict;
import com.kyuloud.ai.agent.eval.EvaluatorService;
import com.kyuloud.ai.agent.planner.Plan;
import com.kyuloud.ai.agent.planner.PlannerService;
import com.kyuloud.ai.agent.tool.RagSearchTool;
import com.kyuloud.ai.agent.tool.ToolProvider;
import com.kyuloud.ai.common.Conversations;
import com.kyuloud.ai.config.AgentLoopProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * Phase 3a — Agent 채팅 서비스.
 *
 * <p>기존 {@link ChatClient}(대화 메모리 advisor 포함)를 재사용하고, 요청별로 도구를
 * 주입한다(전역 {@code defaultTools}를 쓰지 않아 chat/rag 경로에는 영향을 주지 않음).
 * tool-calling 의 ReAct 루프(추론→도구호출→관찰→반복)는 Spring AI 가 자동 처리한다.
 *
 * <p>도구 주입은 {@link ToolProvider} 한 곳으로 모았다 — 공용 도구({@code @Tool} 빈)와 MCP 도구를 합쳐주며,
 * MCP 미설정/연결 실패는 graceful degrade 된다. RAG 검색 도구만 이 (구) 경로 전용이라 별도로 함께 넘긴다.
 */
@Slf4j
@Service
public class AgentService {

    private final ChatClient chatClient;
    private final ToolProvider toolProvider;
    private final RagSearchTool ragSearchTool;
    private final ToolCallTracker toolCallTracker;
    private final PlannerService plannerService;
    private final EvaluatorService evaluatorService;
    private final ClarificationService clarificationService;
    private final AgentLoopProperties loopProperties;
    private final ChatMemory chatMemory;

    @Value("classpath:prompts/system-agent.st")
    private Resource agentSystemPrompt;

    /** Phase 5 — 평가 루프의 행동 실행용 시스템 프롬프트(리서처 페르소나: 한 행동만 조사·보고, 최종 답변 금지). */
    @Value("classpath:prompts/system-loop-action.st")
    private Resource loopActionSystemPrompt;

    public AgentService(ChatClient chatClient,
                        ToolProvider toolProvider,
                        RagSearchTool ragSearchTool,
                        ToolCallTracker toolCallTracker,
                        PlannerService plannerService,
                        EvaluatorService evaluatorService,
                        ClarificationService clarificationService,
                        AgentLoopProperties loopProperties,
                        ChatMemory chatMemory) {
        this.chatClient = chatClient;
        this.toolProvider = toolProvider;
        this.ragSearchTool = ragSearchTool;
        this.toolCallTracker = toolCallTracker;
        this.plannerService = plannerService;
        this.evaluatorService = evaluatorService;
        this.clarificationService = clarificationService;
        this.loopProperties = loopProperties;
        this.chatMemory = chatMemory;
    }

    public AgentResponse chat(String conversationId, String message) {
        String cid = Conversations.resolve(conversationId);
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
        String cid = Conversations.resolve(conversationId);
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
     * <p><b>대화 맥락 처리</b>: 단계 실행은 사용자 대화({@code cid})와 분리된 임시 {@code planCid} 에서 수행하되,
     * 시작 시 사용자 실제 대화 기록을 {@code planCid} 에 <em>시드</em>해 단계들이 이전 맥락(예: 사용자 이름)을 인지한다.
     * 한 plan 안의 단계들은 이 임시 메모리로 앞 단계 결과도 공유한다. 실행이 끝나면 중간 단계 잡음은 버리고
     * (= {@code planCid} 를 {@link ChatMemory#clear}), 사용자 대화({@code cid})에는 <em>원 질문 → 최종 답변</em>
     * 한 턴만 깔끔히 기록한다. 즉 plan 도 {@link #chat} 처럼 대화 맥락을 읽고 이어가되, 내부 단계로 메모리를 오염시키지 않는다.
     */
    public PlanResponse planAndExecute(String conversationId, String message) {
        String cid = Conversations.resolve(conversationId);
        // JDBC 영속 메모리의 conversation_id 컬럼이 VARCHAR(36)(=UUID 크기)이므로 임시 ID 는 36자 UUID 만 사용한다.
        // (접두사/cid 를 덧붙이면 36자를 초과해 적재 시 'value too long' 오류가 난다.)
        String planCid = UUID.randomUUID().toString();
        log.debug("agent plan-and-execute: cid={}, planCid={}, message={}", cid, planCid, message);

        try {
            seedHistory(planCid, cid);

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

            recordTurn(cid, message, finalReply);
            return new PlanResponse(message, plan.steps(), stepResults, finalReply,
                    toolCallTracker.getCalledTools());
        } finally {
            chatMemory.clear(planCid);
            toolCallTracker.reset();
        }
    }

    /**
     * Phase 5 — 평가 기반 에이전트 루프(Reflective Agent).
     *
     * <p>정적인 {@link #planAndExecute} 와 달리 <b>행동마다 평가</b>한다:
     * 질문 파악·계획 → (행동 실행 → 평가자 충분성 판정 → 부족하면 추가/다른 행동)* → 최종 합성.
     * 평가자({@link EvaluatorService})가 "이 근거로 충분히 답할 수 있는가"를 판단해, 충분하면 즉시 종료하고
     * 부족하면 부족분(missing)·다음 행동(nextAction)을 다음 회차에 반영한다. 최대 반복은
     * {@link AgentLoopProperties#getMaxIterations()} 로 제한해 비용·무한루프를 막는다.
     *
     * <p>대화 맥락 처리는 {@link #planAndExecute} 와 동일하다(임시 {@code loopCid} 에 실제 기록 시드 →
     * 종료 시 중간 잡음 폐기 → 사용자 대화엔 원 질문→최종 답변 한 턴만 기록).
     */
    public LoopResponse loop(String conversationId, String message) {
        String cid = Conversations.resolve(conversationId);
        String loopCid = UUID.randomUUID().toString();
        int maxIterations = Math.max(1, loopProperties.getMaxIterations());
        log.debug("agent loop: cid={}, loopCid={}, maxIter={}, message={}", cid, loopCid, maxIterations, message);

        try {
            seedHistory(loopCid, cid);

            // 질문 파악 + 계획 수립. 계획 단계들을 초기 행동 큐로 사용하고, 소진되면 평가자 제안으로 이어간다.
            Plan plan = plannerService.plan(message);
            Deque<String> pendingActions = new ArrayDeque<>();
            for (PlanStep step : plan.steps()) {
                pendingActions.add(step.description());
            }

            List<IterationTrace> iterations = new ArrayList<>();
            List<StepResult> evidence = new ArrayList<>();
            String action = pendingActions.isEmpty()
                    ? "질문에 답하는 데 필요한 정보를 수집하라." : pendingActions.poll();
            String missingHint = null;

            for (int i = 1; i <= maxIterations; i++) {
                // 행동 실행기는 리서처 페르소나: 이번 한 행동만 조사·보고하고 최종 답은 쓰지 않는다(그래야 평가 루프가 의미를 가진다).
                String observation = agentSpec(loopActionSystemPrompt, loopCid,
                        buildLoopActionPrompt(message, action, missingHint)).call().content();
                evidence.add(new StepResult(i, action, observation));

                EvaluationVerdict verdict = evaluatorService.evaluate(message, joinEvidence(evidence));
                iterations.add(new IterationTrace(i, action, observation, verdict.sufficient(), verdict.missing()));

                if (verdict.sufficient()) {
                    break;
                }
                missingHint = verdict.missing();
                // 다음 행동: 남은 계획 단계 우선, 없으면 평가자가 제안한 nextAction. 둘 다 없으면 진전 불가 → 종료.
                if (!pendingActions.isEmpty()) {
                    action = pendingActions.poll();
                } else if (StringUtils.hasText(verdict.nextAction())) {
                    action = verdict.nextAction();
                } else {
                    log.debug("agent loop: 다음 행동 없음 → 조기 종료 (iteration={})", i);
                    break;
                }
            }

            String finalReply = plannerService.synthesize(message, evidence);
            recordTurn(cid, message, finalReply);
            return new LoopResponse(message, plan.steps(), iterations, finalReply,
                    toolCallTracker.getCalledTools());
        } finally {
            chatMemory.clear(loopCid);
            toolCallTracker.reset();
        }
    }

    /**
     * Phase 5b — 명확화(Clarification).
     *
     * <p>RAG 와 반대로, 답하기 위해 부족한 정보를 <em>사용자에게 되물어</em> 채우는 Human-in-the-loop 단계.
     * 답변하지 않고, 이 질문에 제대로 답하려면 무엇을 되물어야 하는지만 판단해 반환한다(되물을 게 없으면
     * {@code needsClarification=false}). 대화 맥락({@code cid} 의 기록)을 함께 보고 이미 아는 정보는 되묻지 않는다.
     * 읽기 전용이라 메모리에 기록하지 않는다(실제 답변 턴은 이후 chat/plan/loop 호출에서 일어난다).
     */
    public ClarifyResponse clarify(String conversationId, String message) {
        String cid = Conversations.resolve(conversationId);
        String context = renderHistory(chatMemory.get(cid));
        ClarificationVerdict verdict = clarificationService.assess(message, context);
        log.debug("agent clarify: cid={}, needsClarification={}, message={}",
                cid, verdict.needsClarification(), message);
        return new ClarifyResponse(message, verdict.needsClarification(), verdict.questions());
    }

    /**
     * 기본 에이전트 시스템 프롬프트({@code system-agent.st})로 요청 스펙을 구성한다. chat/stream/plan 단계 실행이 공유한다.
     */
    private ChatClient.ChatClientRequestSpec agentSpec(String cid, String userMessage) {
        return agentSpec(agentSystemPrompt, cid, userMessage);
    }

    /**
     * 도구 + 메모리(conversationId) 를 결합한 에이전트 요청 스펙을 구성한다. 시스템 프롬프트를 파라미터로 받아
     * 호출 경로별 페르소나를 바꾼다(예: loop 는 리서처 프롬프트). MCP 도구는 연결이 있을 때만 추가된다.
     */
    private ChatClient.ChatClientRequestSpec agentSpec(Resource systemPrompt, String cid, String userMessage) {
        // 공용 도구 + MCP 는 ToolProvider 가 한데 모아준다. RagSearchTool 은 이 (구) 경로 전용이라 추가로 넘긴다
        // (통합 흐름은 RAG 를 도구로 노출하지 않고 KnowledgeRetriever 로 직접 검색해 단일화함, #1=c).
        return chatClient.prompt()
                .system(systemPrompt)
                .user(userMessage)
                .tools(toolProvider.tools(ragSearchTool))
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, cid));
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

    /**
     * Phase 5 — 루프 한 회차의 행동 실행용 프롬프트. 직전 평가에서 지적된 부족분(missingHint)이 있으면 보완하게 한다.
     * 앞서 수집한 내용은 {@code loopCid} 대화 메모리로 전달되므로 프롬프트에 중복 포함하지 않는다.
     */
    private String buildLoopActionPrompt(String question, String action, String missingHint) {
        StringBuilder sb = new StringBuilder();
        sb.append("전체 질문: ").append(question).append("\n\n");
        sb.append("지금 수행할 행동: ").append(action).append("\n");
        if (StringUtils.hasText(missingHint)) {
            sb.append("직전 평가에서 부족하다고 지적된 점(이를 보완하라): ").append(missingHint).append("\n");
        }
        sb.append("이 행동을 수행하고(필요하면 도구를 호출) 수집한 결과·근거를 답하세요. ")
                .append("앞서 수집한 내용은 대화 맥락에 있으니 참고하세요.");
        return sb.toString();
    }

    /** Phase 5 — 평가자에게 넘길, 회차별 행동·결과를 번호 매긴 누적 근거 문자열. */
    private String joinEvidence(List<StepResult> evidence) {
        StringBuilder sb = new StringBuilder();
        for (StepResult e : evidence) {
            sb.append("[").append(e.order()).append("] 행동: ").append(e.description())
                    .append("\n결과: ").append(e.result()).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 사용자 실제 대화 기록을 임시 실행 컨텍스트({@code tempCid})에 시드해, 단계/회차 실행이 이전 맥락을 인지하게 한다.
     * plan/loop 가 공유한다.
     */
    private void seedHistory(String tempCid, String cid) {
        List<Message> history = chatMemory.get(cid);
        if (!history.isEmpty()) {
            chatMemory.add(tempCid, history);
        }
    }

    /** 사용자 대화({@code cid})에 '원 질문 → 최종 답변' 한 턴만 기록한다(중간 잡음 제외). plan/loop 가 공유한다. */
    private void recordTurn(String cid, String userMessage, String reply) {
        chatMemory.add(cid, List.of(new UserMessage(userMessage), new AssistantMessage(reply)));
    }

    /** Phase 5b — 대화 기록을 명확화 판단용 컨텍스트 텍스트로 렌더링한다(역할 라벨 + 내용). */
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
