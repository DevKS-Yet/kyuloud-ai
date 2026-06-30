package com.kyuloud.ai.agent.unified;

import com.kyuloud.ai.agent.dto.StepResult;
import com.kyuloud.ai.agent.eval.EvaluationVerdict;
import com.kyuloud.ai.agent.eval.EvaluatorService;
import com.kyuloud.ai.agent.tool.ToolProvider;
import com.kyuloud.ai.config.AgentBudgetProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
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
 * Phase 6c~6e — RESEARCH 경로: Orchestrator-workers(독립 작업 병렬 + 의존 작업 순차) + Evaluator-optimizer 보강.
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
 * <p><b>보강(6e)</b>: 초기 조사 후 누적 근거를 {@link EvaluatorService 평가자}가 <b>묶음 1회</b> 평가해
 * 충분하면 조기 종료하고, 부족하면 평가자가 제시한 다음 행동을 목표로 보강 워커를 더 투입한다. 반복은
 * {@code maxReinforcements}(라운드) 와 {@link Budget}(전체 LLM·시간) 두 상한이 함께 막는다.
 *
 * <p>정지조건(#4): {@link Budget} 이 소진되면 남은 워커 배치·보강 투입을 멈추고 지금까지의 근거로 best-effort
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
    private final ToolProvider toolProvider;
    private final ChatOptionsFactory chatOptionsFactory;

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
                               ToolProvider toolProvider,
                               ChatOptionsFactory chatOptionsFactory) {
        this.reasonerChatClient = reasonerChatClient;
        this.workerChatClient = workerChatClient;
        this.evaluatorService = evaluatorService;
        this.knowledgeRetriever = knowledgeRetriever;
        this.budgetProperties = budgetProperties;
        this.toolProvider = toolProvider;
        this.chatOptionsFactory = chatOptionsFactory;
    }

    /**
     * RESEARCH 경로 전체. 질문을 워커 하위작업으로 분해 → 독립 작업은 병렬·의존 작업은 순차로 실행해
     * {@link AgentContext#addEvidence 근거 누적} → 합성해 최종 답변을 만든다.
     *
     * <p>order 순으로 훑으며 연속된 독립 작업({@code dependsOnPrevious=false})을 한 배치로 모아 병렬 실행하고,
     * 의존 작업({@code true})을 만나면 그 전까지의 배치를 먼저 모두 마친 뒤(배리어) 그 작업을 단독 수행한다.
     * 초기 조사를 마치면 {@link #reinforce Evaluator-optimizer 보강 루프}(6e)로 근거 충분성을 평가해 부족하면
     * 보강 워커를 더 투입한다. 분해/워커/평가/합성 각 LLM 호출은 {@code ctx.budget()} 로 정지조건을 따른다(#4).
     *
     * @param ctx      요청 컨텍스트(budget·tracer·evidence 공유)
     * @param question 원본 사용자 질문
     * @param context  대화 맥락 텍스트(분해 시 이미 아는 정보 재조사 방지)
     * @return 누적 근거를 종합한 최종 답변
     */
    public String research(AgentContext ctx, String question, String context) {
        WorkerPlan plan = decompose(ctx, question, context);
        List<WorkerTask> tasks = plan.tasks();
        log.debug("orchestrator: {}개 워커 하위작업으로 분해", tasks.size());

        List<WorkerTask> independentBatch = new ArrayList<>();
        for (WorkerTask task : tasks) {
            if (task.dependsOnPrevious()) {
                // 배리어: 지금까지 모은 독립 작업들을 먼저 병렬로 마친 뒤, 의존 작업을 단독 수행한다.
                runBatch(ctx, independentBatch);
                independentBatch.clear();
                runBatch(ctx, List.of(task));
            } else {
                independentBatch.add(task);
            }
        }
        runBatch(ctx, independentBatch);   // 남은 독립 작업 병렬 실행

        reinforce(ctx, question);          // 6e — 충분성 평가→부족하면 보강 워커 추가

        return synthesize(ctx, question);
    }

    /**
     * Phase 6e — Evaluator-optimizer 보강 루프. 초기 조사로 모은 근거가 질문에 충분한지 평가하고, 부족하면
     * 평가자가 제시한 다음 행동({@code nextAction})을 목표로 보강 워커를 1개 더 투입한다. 충분하다고 판정되면
     * 즉시 멈춘다(조기 종료 — 빈약하지 않은 근거엔 비용을 더 쓰지 않음).
     *
     * <p>워커별이 아니라 누적 근거 <b>묶음 1회</b>를 평가해 비용을 아낀다(패턴 매핑). 반복은 두 상한이 함께
     * 막는다: {@code maxReinforcements}(라운드 수)와 {@link Budget}(전체 LLM 호출·시간) — 둘 중 먼저 닿는
     * 쪽에서 멈춘다. 평가 실패는 {@link EvaluatorService} 가 "충분" 폴백을 주므로 루프가 안전하게 종료된다(#4).
     */
    private void reinforce(AgentContext ctx, String question) {
        int maxRounds = budgetProperties.getMaxReinforcements();
        for (int round = 1; round <= maxRounds; round++) {
            if (ctx.budget().isExhausted()) {
                log.warn("orchestrator: 예산 소진 → 보강 평가 중단(라운드 {}), 지금까지 근거로 합성", round);
                return;
            }
            ctx.budget().accountLlmCall("evaluate");
            EvaluationVerdict verdict = evaluatorService.evaluate(question, formatEvidence(ctx.evidence()));
            if (verdict.sufficient()) {
                log.debug("orchestrator: 근거 충분 → 보강 종료(조기 종료, 라운드 {})", round);
                return;
            }

            String objective = reinforcementObjective(verdict);
            if (!StringUtils.hasText(objective)) {
                log.debug("orchestrator: 부족하나 보강할 구체 행동 없음 → 보강 종료");
                return;
            }
            if (ctx.budget().isExhausted()) {
                log.warn("orchestrator: 예산 소진 → 보강 워커 미투입(라운드 {})", round);
                return;
            }

            int nextOrder = ctx.evidence().size() + 1;
            WorkerTask task = new WorkerTask(nextOrder, objective, false);
            log.debug("orchestrator: 근거 부족(missing={}) → 보강 워커[{}] 투입: {}",
                    verdict.missing(), nextOrder, objective);
            ctx.budget().accountLlmCall("reinforce-worker-" + nextOrder);
            ctx.addEvidence(runWorker(ctx, task));
        }
        log.debug("orchestrator: 보강 라운드 상한({}) 도달 → 합성", maxRounds);
    }

    /** 보강 워커의 조사 목표 — 평가자가 제시한 다음 행동(nextAction)을 우선 쓰고, 없으면 부족분(missing)으로 대체. */
    private String reinforcementObjective(EvaluationVerdict verdict) {
        if (StringUtils.hasText(verdict.nextAction())) {
            return verdict.nextAction();
        }
        return verdict.missing();
    }

    /**
     * 한 배치를 실행한다 — 1개면 그대로, 여러 개면 가상스레드로 병렬 실행해 근거를 누적한다. 빈 배치는 건너뛴다.
     *
     * <p>정지조건(#4): 이미 근거가 하나라도 있고 예산이 소진됐으면 이 배치를 통째로 건너뛰어 best-effort 로
     * 합성으로 넘어간다(최소 1개 배치는 보장 — 첫 배치는 evidence 가 비어 있어 항상 수행). 정밀한 워커당 차단이
     * 아니라 배치 경계에서 게이팅하므로, 병렬 배치 안의 워커들은 함께 수행된다.
     */
    private void runBatch(AgentContext ctx, List<WorkerTask> batch) {
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
            ctx.budget().accountLlmCall("worker-" + task.order());
            ctx.addEvidence(runWorker(ctx, task));
            return;
        }
        runParallel(ctx, batch);
    }

    /**
     * 독립 워커들을 가상스레드로 동시에 실행한다. 제출 순서(=order)대로 결과를 거둬 근거에 누적하므로 순서가
     * 보존된다. 개별 워커가 실패하면 그 근거만 건너뛰고 나머지는 합성에 쓴다(best-effort). 같은 {@link CallTracer}
     * 를 공유하지만 내부가 {@code CopyOnWriteArrayList} 라 병렬 도구추적이 안전하다.
     */
    private void runParallel(AgentContext ctx, List<WorkerTask> batch) {
        log.debug("orchestrator: 독립 워커 {}개 병렬 실행", batch.size());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<StepResult>> futures = new ArrayList<>(batch.size());
            for (WorkerTask task : batch) {
                ctx.budget().accountLlmCall("worker-" + task.order());
                futures.add(executor.submit(() -> runWorker(ctx, task)));
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
        ctx.budget().accountLlmCall("orchestrator-decompose");
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
    private StepResult runWorker(AgentContext ctx, WorkerTask task) {
        String docContext = knowledgeRetriever.retrieveContext(task.objective());
        String userMessage = StringUtils.hasText(docContext)
                ? "참고 문서:\n" + docContext + "\n\n위 문서가 관련 있으면 근거로 삼고 출처를 밝히세요. 관련 없으면 무시하세요.\n\n조사 목표:\n" + task.objective()
                : "조사 목표:\n" + task.objective();

        String finding = workerChatClient.prompt()
                .system(workerSystemPrompt)
                .user(userMessage)
                .tools(toolProvider.tools())
                .options(chatOptionsFactory.forRequest(ctx))   // Phase 7, D4: 워커는 선택 모델 (Phase 8b: 팩토리 경유)
                .toolContext(ctx.tracer().asToolContext())
                .call()
                .content();
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

        String prompt = "원래 질문:\n" + question + "\n\n워커별 조사 근거:\n" + formatEvidence(evidence)
                + "\n위 근거를 종합해 원래 질문에 대한 최종 답변을 작성하세요.";

        ctx.budget().accountLlmCall("orchestrator-synthesize");
        return reasonerChatClient.prompt()
                .system(synthesisSystemPrompt)
                .user(prompt)
                .call()
                .content();
    }

    /** 누적 근거를 "[근거 N] 목표 → 결과" 블록 텍스트로 렌더링한다(평가·합성 입력 공용). */
    private String formatEvidence(List<StepResult> evidence) {
        StringBuilder sb = new StringBuilder();
        for (StepResult e : evidence) {
            sb.append("[근거 ").append(e.order()).append("] ").append(e.description())
                    .append("\n→ ").append(e.result()).append("\n\n");
        }
        return sb.toString();
    }

    private WorkerPlan singleTaskFallback(String question) {
        return new WorkerPlan(List.of(new WorkerTask(1, question, false)));
    }
}
