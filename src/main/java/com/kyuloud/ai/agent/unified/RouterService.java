package com.kyuloud.ai.agent.unified;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Phase 6 — Router(Routing 패턴). 진입 분류기로, 사용자 질문과 대화 맥락을 보고 처리 전략 하나를 고른다.
 *
 * <p>약한 로컬 모델이 한 번의 호출로 좁은 판단(전략 enum)만 하도록 설계한다. 구조화 출력({@link RouteDecision})에
 * 실패하거나 전략이 비면 {@link RouteStrategy#DIRECT} 로 폴백한다(저비용·안전한 기본 경로, #2).
 */
@Slf4j
@Service
public class RouterService {

    private final ChatClient routerChatClient;

    @Value("classpath:prompts/system-router.st")
    private Resource routerSystemPrompt;

    public RouterService(@Qualifier("routerChatClient") ChatClient routerChatClient) {
        this.routerChatClient = routerChatClient;
    }

    /**
     * 질문을 전략으로 분류한다. {@code conversationContext} 는 이미 아는 정보로 불필요한 CLARIFY 를 줄이는 데 쓴다.
     */
    public RouteDecision route(String question, String conversationContext) {
        String user = "대화 맥락:\n" + (StringUtils.hasText(conversationContext) ? conversationContext : "(없음)")
                + "\n\n사용자 질문:\n" + question
                + "\n\n이 질문을 어떤 전략으로 처리할지 하나만 고르세요.";
        try {
            RouteDecision decision = routerChatClient.prompt()
                    .system(routerSystemPrompt)
                    .user(user)
                    .call()
                    .entity(RouteDecision.class);

            if (decision == null || decision.strategy() == null) {
                log.debug("Router: 분류 결과 없음 → DIRECT 폴백");
                return new RouteDecision(RouteStrategy.DIRECT, "분류 실패 폴백");
            }
            log.debug("Router: {} ({})", decision.strategy(), decision.reason());
            return decision;
        } catch (Exception e) {
            log.warn("Router: 분류 실패 → DIRECT 폴백: {}", e.getMessage());
            return new RouteDecision(RouteStrategy.DIRECT, "분류 예외 폴백");
        }
    }
}
