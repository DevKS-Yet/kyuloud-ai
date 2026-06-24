package com.kyuloud.ai.agent.planner;

import com.kyuloud.ai.agent.dto.PlanStep;
import com.kyuloud.ai.agent.dto.StepResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Phase 3d-4 — Planner.
 *
 * <p>복잡한 multi-step 요청을 "계획 수립 → 단계별 실행 → 결과 합성"으로 분리하기 위한 계획·합성 담당.
 * 단계 <em>실행</em>(도구 호출 ReAct 루프)은 {@code AgentService} 가 도구 탑재 {@code ChatClient} 로 수행하고,
 * 이 서비스는 도구·메모리가 없는 전용 {@code plannerChatClient} 로 다음 두 가지만 담당한다.
 *
 * <ol>
 *   <li>{@link #plan(String)} — 요청을 순서가 있는 단계 목록({@link Plan})으로 분해(구조화 출력).</li>
 *   <li>{@link #synthesize(String, List)} — 단계별 실행 결과를 원 요청에 답하는 하나의 최종 답변으로 합성.</li>
 * </ol>
 */
@Slf4j
@Service
public class PlannerService {

    private final ChatClient plannerChatClient;

    @Value("classpath:prompts/system-planner.st")
    private Resource plannerSystemPrompt;

    @Value("classpath:prompts/system-synthesis.st")
    private Resource synthesisSystemPrompt;

    public PlannerService(@Qualifier("plannerChatClient") ChatClient plannerChatClient) {
        this.plannerChatClient = plannerChatClient;
    }

    /**
     * 사용자 요청을 순서가 있는 실행 단계로 분해한다. 구조화 출력(JSON)으로 {@link Plan} 을 직접 생성한다.
     * 분해에 실패하거나 빈 계획이면 원 요청을 그대로 수행하는 단일 단계로 폴백한다.
     */
    public Plan plan(String request) {
        try {
            Plan plan = plannerChatClient.prompt()
                    .system(plannerSystemPrompt)
                    .user(request)
                    .call()
                    .entity(Plan.class);

            if (plan == null || plan.steps() == null || plan.steps().isEmpty()) {
                log.debug("Planner: 빈 계획 → 단일 단계 폴백");
                return singleStepFallback(request);
            }
            log.debug("Planner: {}단계 계획 수립", plan.steps().size());
            return plan;
        } catch (Exception e) {
            log.warn("Planner: 계획 수립 실패 → 단일 단계 폴백: {}", e.getMessage());
            return singleStepFallback(request);
        }
    }

    /**
     * 단계별 실행 결과를 종합해 원 요청에 답하는 최종 답변을 생성한다.
     */
    public String synthesize(String request, List<StepResult> stepResults) {
        StringBuilder sb = new StringBuilder();
        sb.append("원래 요청:\n").append(request).append("\n\n단계별 실행 결과:\n");
        for (StepResult step : stepResults) {
            sb.append("[단계 ").append(step.order()).append("] ").append(step.description())
                    .append("\n→ ").append(step.result()).append("\n\n");
        }
        sb.append("위 단계별 결과를 종합해 원래 요청에 대한 최종 답변을 작성하세요.");

        return plannerChatClient.prompt()
                .system(synthesisSystemPrompt)
                .user(sb.toString())
                .call()
                .content();
    }

    private Plan singleStepFallback(String request) {
        return new Plan(List.of(new PlanStep(1, request)));
    }
}
