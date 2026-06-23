package com.kyuloud.ai.chat.memory;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phase 1c — 대화 메모리 구성.
 * InMemory 저장소 + 최근 N개 메시지 윈도우. (이후 Phase 4에서 JDBC 영속화로 교체 가능)
 */
@Configuration
public class ChatMemoryConfig {

    private static final int MAX_MESSAGES = 20;

    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(MAX_MESSAGES)
                .build();
    }
}
