package com.kyuloud.ai.agent.unified;

import com.kyuloud.ai.agent.tool.ToolProvider;
import com.kyuloud.ai.common.ChatMemories;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Phase 8c — DIRECT 라우트 핸들러(기존 {@code UnifiedAgentService.direct} 이동).
 *
 * <p>항상 1회 문서 검색(threshold 필터)으로 컨텍스트를 곁들이고, 도구를 갖춘 단발 답변을 만든다. 대화 맥락은
 * 메모리 advisor 가 아니라 읽어온 {@code history} 를 직접 메시지로 주입해 명시적으로 다룬다. 도구 추적은
 * {@code ToolContext} 에 실은 {@link CallTracer} 로 수집하고, 생성 모델은 사용자가 고른 {@code ctx.model()}
 * 로 per-request 오버라이드한다(Phase 7, D4 — 옵션은 {@link ChatOptionsFactory} 경유, Phase 8b).
 *
 * <p>CLARIFY 과민 폴백의 목적지이기도 하다. 이때 {@code routed} 는 Router 원본 분류(CLARIFY)를 그대로 노출하고
 * {@code executed} 만 DIRECT 가 된다(둘을 함께 보여 라우팅 동작을 관찰).
 */
@Slf4j
@Component
public class DirectRouteHandler implements RouteHandler {

    private final KnowledgeRetriever knowledgeRetriever;
    private final ChatClient workerChatClient;
    private final ToolProvider toolProvider;
    private final ChatOptionsFactory chatOptionsFactory;
    private final ChatMemory chatMemory;

    @Value("classpath:prompts/system-direct.st")
    private Resource directSystemPrompt;

    public DirectRouteHandler(KnowledgeRetriever knowledgeRetriever,
                              @Qualifier("workerChatClient") ChatClient workerChatClient,
                              ToolProvider toolProvider,
                              ChatOptionsFactory chatOptionsFactory,
                              ChatMemory chatMemory) {
        this.knowledgeRetriever = knowledgeRetriever;
        this.workerChatClient = workerChatClient;
        this.toolProvider = toolProvider;
        this.chatOptionsFactory = chatOptionsFactory;
        this.chatMemory = chatMemory;
    }

    @Override
    public RouteStrategy strategy() {
        return RouteStrategy.DIRECT;
    }

    @Override
    public UnifiedAgentResponse handle(AgentContext ctx, RouteRequest request) {
        String reply = direct(ctx, request.history(), request.message());
        ChatMemories.recordTurn(chatMemory, ctx.conversationId(), request.message(), reply);
        // routed 는 Router 원본 분류를 그대로(폴백이면 CLARIFY), executed 는 DIRECT.
        return new UnifiedAgentResponse(request.message(), request.routed(), RouteStrategy.DIRECT,
                reply, List.of(), ctx.evidence(), ctx.tracer().getCalledTools(), ctx.model());
    }

    private String direct(AgentContext ctx, List<Message> history, String message) {
        String docContext = knowledgeRetriever.retrieveContext(message);
        String userMessage = StringUtils.hasText(docContext)
                ? "참고 문서:\n" + docContext + "\n\n위 문서가 질문과 관련 있으면 근거로 삼아 답하세요(출처 표기). "
                        + "관련 없으면 무시하세요.\n\n질문:\n" + message
                : message;

        ctx.budget().accountLlmCall("direct");
        return workerChatClient.prompt()
                .system(directSystemPrompt)
                .messages(history)
                .user(userMessage)
                .tools(toolProvider.tools())
                .options(chatOptionsFactory.forRequest(ctx))
                .toolContext(ctx.tracer().asToolContext())
                .call()
                .content();
    }
}
