package com.kyuloud.ai.common.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

/**
 * Phase 4a — 관측(observability) 로깅 Advisor.
 *
 * <p>모든 {@code ChatClient} 호출을 가장 바깥에서 감싸 요청(사용자 메시지)과 응답(답변·토큰 사용량·지연)을
 * 로깅한다. {@link BaseAdvisor} 를 구현해 blocking({@code call})·스트리밍({@code stream}) 경로를 모두 처리한다.
 *
 * <p>지연(latency) 측정을 위해 {@link #before}에서 시작 시각을 요청 컨텍스트에 담고 {@link #after}에서 읽는다.
 * 메모리/RAG/도구 advisor 보다 바깥에서 돌도록 order 를 메모리 advisor 보다 한 단계 낮춰(=더 높은 우선순위)
 * 전체 호출 시간을 포함해 측정한다.
 */
@Slf4j
@Component
public class LoggingAdvisor implements BaseAdvisor {

    /** 시작 시각(ns)을 담는 요청 컨텍스트 키. */
    private static final String START_TIME_KEY = "kyuloud.logging.startNanos";

    /** 로그 한 줄에 남길 프롬프트/응답 본문 최대 길이(과도한 로그 방지). */
    private static final int MAX_LOG_LENGTH = 500;

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain advisorChain) {
        log.info("[LLM 요청] {}", truncate(userText(request)));
        return request.mutate()
                .context(START_TIME_KEY, System.nanoTime())
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain advisorChain) {
        ChatResponse chatResponse = response.chatResponse();
        String reply = chatResponse != null && chatResponse.getResult() != null
                ? chatResponse.getResult().getOutput().getText() : "";
        log.info("[LLM 응답] {} | {} | {}",
                elapsed(response), formatUsage(chatResponse), truncate(reply));
        return response;
    }

    /**
     * 메모리 advisor({@link Advisor#DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER}) 보다 더 바깥에서 감싸도록
     * 한 단계 낮은 order 를 부여한다(낮을수록 먼저 before/나중 after = 더 바깥).
     */
    @Override
    public int getOrder() {
        return Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER - 100;
    }

    private String userText(ChatClientRequest request) {
        try {
            return request.prompt().getUserMessage().getText();
        } catch (Exception e) {
            return request.prompt().getContents();
        }
    }

    private String elapsed(ChatClientResponse response) {
        Object start = response.context().get(START_TIME_KEY);
        if (start instanceof Long startNanos) {
            return (System.nanoTime() - startNanos) / 1_000_000 + "ms";
        }
        return "elapsed=n/a";
    }

    private String formatUsage(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getMetadata() == null
                || chatResponse.getMetadata().getUsage() == null) {
            return "tokens=n/a";
        }
        Usage usage = chatResponse.getMetadata().getUsage();
        return "tokens(prompt=" + usage.getPromptTokens()
                + ", completion=" + usage.getCompletionTokens()
                + ", total=" + usage.getTotalTokens() + ")";
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() > MAX_LOG_LENGTH
                ? oneLine.substring(0, MAX_LOG_LENGTH) + "…" : oneLine;
    }
}
