package com.kyuloud.ai.rag.retrieval;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Phase 2a — RAG 질의 서비스.
 * QuestionAnswerAdvisor 로 VectorStore 검색 결과를 프롬프트 context 로 주입한다.
 * (기존 ChatClient 의 메모리 advisor 도 함께 동작 → RAG + 멀티턴)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagChatService {

    private static final String DEFAULT_CONVERSATION_ID = "default";

    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    public String chat(String conversationId, String message) {
        String cid = StringUtils.hasText(conversationId) ? conversationId : DEFAULT_CONVERSATION_ID;
        log.debug("rag chat: cid={}, message={}", cid, message);

        return chatClient.prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, cid))
                .advisors(QuestionAnswerAdvisor.builder(vectorStore).build())
                .call()
                .content();
    }
}
