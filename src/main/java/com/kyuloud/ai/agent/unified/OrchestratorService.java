package com.kyuloud.ai.agent.unified;

import com.kyuloud.ai.agent.dto.StepResult;
import com.kyuloud.ai.agent.eval.EvaluationVerdict;
import com.kyuloud.ai.agent.eval.EvaluatorService;
import com.kyuloud.ai.agent.tool.DateTimeTool;
import com.kyuloud.ai.agent.tool.DocumentCatalogTool;
import com.kyuloud.ai.agent.tool.WebSearchTool;
import com.kyuloud.ai.config.AgentBudgetProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Phase 6c~6d — RESEARCH 경로: Orchestrator-workers(독립 작업 병렬 + 의존 작업 순차).
 *
 * <p>Anthropic "Building Effective Agents" 의 Orchestrator-workers 패턴을 워크플로로 구현한다:
 * 중앙 LLM(orchestrator)이 다단계 질문을 조사 가능한 워커 하위작업으로 <b>동적 분해</b>한 뒤, 각 워커가
 * 리서처 페르소나로 도구를 써서 자기 목표에 대한 <em>근거만</em> 보고하고, 누적된 근거를 마지막에 하나의
 * 답변으로 <b>합성</b>한다.
 *
 * <p><b>병렬화(6d)</b>: 서로 독립인 워커({@code dependsOnPrevious=false})는 가상스레드로 동시에 실행해 지연을
 * 줄이고, 앞선 결과에 의존하는 워커는 배리어로 그 전까지를 모두 마친 뒤 순차 실행한다. 같은 요청의 워커들은
 * 하나의 {@link CallTracer}(내부 {@code CopyOnWriteArrayList})를 공유하므로 병렬 도구추적이 안전하다
 * (6a 에서 PoC 검증한 메커니즘). {@code spring.threads.virtual.enabled=true} 전제.
 *
 * <p>역할별로 클라이언트를 나눈다(약한 로컬 모델의 판단 부담 최소화):
 * <ul>
 *   <li>분해·합성 — 도구 없는 reasoner({@code plannerChatClient}). 섣불리 도구를 부르지 않고 추론만.</li>
 *   <li>워커 실행 — 도구를 갖춘 {@code workerChatClient}. DIRECT 와 같은 도구셋 + 요청별 {@link CallTracer}.</li>
 * </ul>
 *
 * <p>정지조건(#4): {@link Budget} 이 소진되면 남은 워커 배치 투입을 멈추고 지금까지의 근거로 best-effort
 * 합성한다(단, 최소 1개 배치는 반드시 수행해 빈 근거 합성을 피한다).
 */
@Slf4j
@Service
public class OrchestratorService {

    private final ChatClient reasonerChatClient;
    private final ChatClient workerChatClient;
    private final EvaluatorService evaluatorService;
    private final KnowledgeRetriever knowledgeRetriever;
    private final AgentBudgetProperties budgetProperties;
    private final DateTimeTool dateTimeTool;
    private final WebSearchTool webSearchTool;
    private final DocumentCatalogTool documentCatalogTool;

    @Value("classpath:prompts/system-orchestrator.st")
    private Resource orchestratorSystemPrompt;

    @Value("classpath:prompts/system-worker.st")
    private Resource workerSystemPrompt;

    @Value("classpath:prompts/system-synthesis.st")
    private Resource synthesisSystemPrompt;

    public OrchestratorService(@Qualifier("plannerChatClient") ChatClient reasonerChatClient,
                               @Qualifier("workerChatClient") ChatClient workerChatClient,
                               EvaluatorService evaluatorService,
                               KnowledgeRetriever knowledgeRetriever,
                               AgentBudgetProperties budgetProperties,
                               DateTimeTool dateTimeTool,
                               WebSearchTool webSearchTool,
                               DocumentCatalogTool documentCatalogTool) {
        this.reasonerChatClient = reasonerChatClient;
        this.workerChatClient = workerChatClient;
        this.evaluatorService = evaluatorService;
        this.knowledgeRetriever = knowledgeRetriever;
        this.budgetProperties = budgetProperties;
        this.dateTimeTool = dateTimeTool;
        this.webSearchTool = webSearchTool;
        this.documentCatalogTool = documentCatalogTool;
    }

    /**
     * RESEARCH 경로 전체. 질문을 워커 하위작업으로 분해 → 독립 작업은 병렬·의존 작업은 순차로 실행해
     * {@link AgentContext#addEvidence 근거 누적} → 합성해 최종 답변을 만든다.
     *
     * <p>order 순으로 훑으며 연속된 독립 작업({@code dependsOnPrevious=false})을 한 배치로 모아 병렬 실행하고,
     * 의존 작업({@code true})을 만나면 그 전까지의 배치를 먼저 모두 마친 뒤(배리어) 그 작업을 단독 수행한다.
     * 분해/워커/합성 각 LLM 호출은 {@code ctx.budget()} 로 정지조건을 따른다(배치 단위로 게이팅, #4).
     *
     * @param ctx      요청 컨텍스트(budget·tracer·evidence 공유)
     * @param question 원본 사용자 질문
     * @param context  대화 맥락 텍스트(분해 시 이미 아는 정보 재조사 방지)
     * @param mcp      외부 MCP 도구 콜백(없으면 빈 배열). 워커에게 DIRECT 와 동일 도구셋을 주기 위해 받는다.
     * @return 누적 근거를 종합한 최종 답변
     */
    public String research(AgentContext ctx, String question, String context, ToolCallback[] mcp) {
        WorkerPlan plan = decompose(ctx, question, context);
        List<WorkerTask> tasks = plan.tasks();
        log.debug("orchestrator: {}개 워커 하위작업으로 분해", tasks.size());

        List<WorkerTask> independentBatch = new ArrayList<>();
        for (WorkerTask task : tasks) {
            if (task.dependsOnPrevious()) {
                // 배리어: 지금까지 모은 독립 작업들을 먼저 병렬로 마친 뒤, 의존 작업을 단독 수행한다.
                runBatch(ctx, independentBatch, mcp);
                independentBatch.clear();
                runBatch(ctx, List.of(task), mcp);
            } else {
                independentBatch.add(task);
            }
        }
        runBatch(ctx, independentBatch, mcp);   // 남은 독립 작업 병렬 실행

        return synthesize(ctx, question);
    }

    /**
     * 한 배치를 실행한다 — 1개면 그대로, 여러 개면 가상스레드로 병렬 실행해 근거를 누적한다. 빈 배치는 건너뛴다.
     *
     * <p>정지조건(#4): 이미 근거가 하나라도 있고 예산이 소진됐으면 이 배치를 통째로 건너뛰어 best-effort 로
     * 합성으로 넘어간다(최소 1개 배치는 보장 — 첫 배치는 evidence 가 비어 있어 항상 수행). 정밀한 워커당 차단이
     * 아니라 배치 경계에서 게이팅하므로, 병렬 배치 안의 워커들은 함께 수행된다.
     */
    private void runBatch(AgentContext ctx, List<WorkerTask> batch, ToolCallback[] mcp) {
        if (batch.isEmpty()) {
            return;
        }
        if (!ctx.evidence().isEmpty() && ctx.budget().isExhausted()) {
            log.warn("orchestrator: 예산 소진 → 워커 배치 {}개 건너뜀, 지금까지 근거 {}개로 합성",
                    batch.size(), ctx.evidence().size());
            return;
        }
        if (batch.size() == 1) {
            WorkerTask task = batch.get(0);
            accountLlmCall(ctx, "worker-" + task.order());
            ctx.addEvidence(runWorker(ctx, task, mcp));
            return;
        }
        runParallel(ctx, batch, mcp);
    }

    /**
     * 독립 워커들을 가상스레드로 동시에 실행한다. 제출 순서(=order)대로 결과를 거둬 근거에 누적하므로 순서가
     * 보존된다. 개별 워커가 실패하면 그 근거만 건너뛰고 나머지는 합성에 쓴다(best-effort). 같은 {@link CallTracer}
     * 를 공유하지만 내부가 {@code CopyOnWriteArrayList} 라 병렬 도구추적이 안전하다.
     */
    private void runParallel(AgentContext ctx, List<WorkerTask> batch, ToolCallback[] mcp) {
        log.debug("orchestrator: 독립 워커 {}개 병렬 실행", batch.size());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<StepResult>> futures = new ArrayList<>(batch.size());
            for (WorkerTask task : batch) {
                accountLlmCall(ctx, "worker-" + task.order());
                futures.add(executor.submit(() -> runWorker(ctx, task, mcp)));
            }
            for (Future<StepResult> future : futures) {
                try {
                    ctx.addEvidence(future.get());
                } catch (ExecutionException e) {
                    log.warn("orchestrator: 병렬 워커 실패 → 해당 근거 생략: {}",
                            e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("orchestrator: 병렬 워커 대기 중 인터럽트 → 남은 근거 생략");
                    break;
                }
            }
        }
    }

    /**
     * 질문을 워커 하위작업 묶음으로 동적 분해한다(구조화 출력). 실패하거나 빈 계획이면 원 질문을 그대로 수행하는
     * 단일 하위작업으로 폴백한다(RESEARCH 로 분기했으니 최소 1개는 돌린다).
     */
    private WorkerPlan decompose(AgentContext ctx, String question, String context) {
        accountLlmCall(ctx, "orchestrator-decompose");
        String userMessage = StringUtils.hasText(context)
                ? "대화 맥락:\n" + context + "\n\n질문:\n" + question
                : question;
        try {
            WorkerPlan plan = reasonerChatClient.prompt()
                    .system(orchestratorSystemPrompt)
                    .user(userMessage)
                    .call()
                    .entity(WorkerPlan.class);
            if (plan == null || plan.tasks() == null || plan.tasks().isEmpty()) {
                log.debug("orchestrator: 빈 분해 결과 → 단일 워커 폴백");
                return singleTaskFallback(question);
            }
            return plan;
        } catch (Exception e) {
            log.warn("orchestrator: 분해 실패 → 단일 워커 폴백: {}", e.getMessage());
            return singleTaskFallback(question);
        }
    }

    /**
     * 워커 1개를 실행한다 — 리서처 페르소나로 자기 조사 목표에 대한 근거만 보고한다. DIRECT 와 같은 도구셋과
     * 요청별 {@link CallTracer}({@code ToolContext})를 쓰고, 목표에 맞춰 항상 1회 문서 검색 컨텍스트를 곁들인다.
     */
    private StepResult runWorker(AgentContext ctx, WorkerTask task, ToolCallback[] mcp) {
        String docContext = knowledgeRetriever.retrieveContext(task.objective());
        String userMessage = StringUtils.hasText(docContext)
                ? "참고 문서:\n" + docContext + "\n\n위 문서가 관련 있으면 근거로 삼고 출처를 밝히세요. 관련 없으면 무시하세요.\n\n조사 목표:\n" + task.objective()
                : "조사 목표:\n" + task.objective();

        var spec = workerChatClient.prompt()
                .system(workerSystemPrompt)
                .user(userMessage)
                .tools(dateTimeTool, webSearchTool, documentCatalogTool)
                .toolContext(ctx.tracer().asToolContext());
        if (mcp.length > 0) {
            spec = spec.tools((Object[]) mcp);
        }
        String finding = spec.call().content();
        log.debug("orchestrator: 워커[{}] 완료 — {}", task.order(), task.objective());
        return new StepResult(task.order(), task.objective(), finding);
    }

    /** 누적된 워커 근거를 종합해 원 질문에 답하는 최종 답변을 만든다(예산 소진 시에도 best-effort 로 1회 수행). */
    private String synthesize(AgentContext ctx, String question) {
        List<StepResult> evidence = ctx.evidence();
        if (evidence.isEmpty()) {
            // 이론상 도달하지 않음(최소 1워커 보장). 방어적으로 솔직히 모름을 알린다.
            log.warn("orchestrator: 근거 없이 합성 요청됨");
            return "조사를 통해 답변에 필요한 근거를 확보하지 못했습니다. 질문을 더 구체적으로 알려주시면 다시 조사하겠습니다.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("원래 질문:\n").append(question).append("\n\n워커별 조사 근거:\n");
        for (StepResult e : evidence) {
            sb.append("[근거 ").append(e.order()).append("] ").append(e.description())
                    .append("\n→ ").append(e.result()).append("\n\n");
        }
        sb.append("위 근거를 종합해 원래 질문에 대한 최종 답변을 작성하세요.");

        accountLlmCall(ctx, "orchestrator-synthesize");
        return reasonerChatClient.prompt()
                .system(synthesisSystemPrompt)
                .user(sb.toString())
                .call()
                .content();
    }

    private WorkerPlan singleTaskFallback(String question) {
        return new WorkerPlan(List.of(new WorkerTask(1, question, false)));
    }

    /**
     * LLM 호출 1회를 예산에 반영한다. 초과해도 막지 않고 경고만 한다(실제 워커 투입 차단은 {@code research} 의
     * 루프가 {@link Budget#isExhausted()} 로 한다 — 이 메서드는 정확한 사용량 집계·관측이 목적).
     */
    private void accountLlmCall(AgentContext ctx, String purpose) {
        if (!ctx.budget().tryConsumeLlmCall()) {
            log.warn("orchestrator: 예산 초과 상태에서 LLM 호출({}) — best-effort 진행 (used={}/{})",
                    purpose, ctx.budget().usedLlmCalls(), ctx.budget().maxLlmCalls());
        }
    }
}
