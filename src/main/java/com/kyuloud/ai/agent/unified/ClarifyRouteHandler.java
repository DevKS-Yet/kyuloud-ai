package com.kyuloud.ai.agent.unified;

import com.kyuloud.ai.agent.clarify.ClarificationService;
import com.kyuloud.ai.agent.clarify.ClarificationVerdict;
import com.kyuloud.ai.agent.dto.ClarifyingQuestion;
import com.kyuloud.ai.common.ChatMemories;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Phase 8c — CLARIFY 라우트 핸들러(기존 {@code UnifiedAgentService.tryClarify} 이동).
 *
 * <p>답변하지 않고, 제대로 답하기 위해 사용자에게 되물을 핵심 정보를 질문+선택지로 만든다. 대화 맥락을 반영해
 * 이미 아는 정보는 묻지 않는다. 되물을 게 있으면 그 턴(원 질문 → 되묻기)을 메모리에 기록해, 사용자가 같은
 * {@code conversationId} 로 답을 더해 재호출하면 맥락이 이어지게 한다(연속성, #5).
 *
 * <p>명확화 전문가가 되물을 게 없다고 판단하면(Router 의 CLARIFY 가 과했던 경우) {@code null} 을 반환해
 * 호출부가 DIRECT 핸들러로 위임하게 한다. 명확화 텍스트는 내부 역할(기본 모델)이 만든다(D4: 선택 모델은
 * DIRECT/워커 전용) → {@code executedModel} = 기본 모델.
 */
@Slf4j
@Component
public class ClarifyRouteHandler implements RouteHandler {

    private final ClarificationService clarificationService;
    private final ModelCatalog modelCatalog;
    private final ChatMemory chatMemory;

    public ClarifyRouteHandler(ClarificationService clarificationService,
                               ModelCatalog modelCatalog,
                               ChatMemory chatMemory) {
        this.clarificationService = clarificationService;
        this.modelCatalog = modelCatalog;
        this.chatMemory = chatMemory;
    }

    @Override
    public RouteStrategy strategy() {
        return RouteStrategy.CLARIFY;
    }

    @Override
    public UnifiedAgentResponse handle(AgentContext ctx, RouteRequest request) {
        ctx.budget().accountLlmCall("clarify");
        ClarificationVerdict verdict = clarificationService.assess(request.message(), request.contextText());
        List<ClarifyingQuestion> questions = verdict.questions();
        if (!verdict.needsClarification() || questions == null || questions.isEmpty()) {
            log.debug("clarify: Router=CLARIFY 였으나 되물을 정보 없음 → DIRECT 위임(null 반환)");
            return null;
        }

        String rendered = renderClarification(questions);
        // 연속성(#5): 되묻기를 한 턴으로 기록해 재호출 시 맥락 유지.
        ChatMemories.recordTurn(chatMemory, ctx.conversationId(), request.message(), rendered);
        return new UnifiedAgentResponse(request.message(), RouteStrategy.CLARIFY, RouteStrategy.CLARIFY,
                rendered, questions, List.of(), ctx.tracer().getCalledTools(), modelCatalog.defaultModel());
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
}
