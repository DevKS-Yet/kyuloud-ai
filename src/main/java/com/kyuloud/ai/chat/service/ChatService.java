package com.kyuloud.ai.chat.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * Phase 1a — 단발 채팅 서비스.
 * ChatClient 로 LLM 에 질의하고 응답 텍스트를 반환한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatClient chatClient;

    public String chat(String message) {
        log.debug("chat request: {}", message);
        return chatClient.prompt()
                .user(message)
                .call()
                .content();
    }

    /**
     * Phase 1b — 토큰 단위 스트리밍 응답.
     */
    public Flux<String> stream(String message) {
        log.debug("stream request: {}", message);
        return chatClient.prompt()
                .user(message)
                .stream()
                .content();
    }
}
