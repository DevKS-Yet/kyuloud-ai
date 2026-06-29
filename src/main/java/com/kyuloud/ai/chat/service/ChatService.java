package com.kyuloud.ai.chat.service;

import com.kyuloud.ai.common.Conversations;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * Phase 1 — 채팅 서비스.
 * ChatClient 로 LLM 에 질의하며, conversationId 기준으로 대화 메모리를 유지한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatClient chatClient;

    public String chat(String conversationId, String message) {
        String cid = Conversations.resolve(conversationId);
        log.debug("chat request: cid={}, message={}", cid, message);
        return chatClient.prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, cid))
                .call()
                .content();
    }

    /**
     * Phase 1b — 토큰 단위 스트리밍 응답.
     */
    public Flux<String> stream(String conversationId, String message) {
        String cid = Conversations.resolve(conversationId);
        log.debug("stream request: cid={}, message={}", cid, message);
        return chatClient.prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, cid))
                .stream()
                .content();
    }
}
