package com.kyuloud.ai.chat.memory;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 대화 메모리 구성.
 *
 * <p>Phase 1c — 최근 N개 메시지 윈도우({@link MessageWindowChatMemory}).
 * <p>Phase 4d — 저장소를 InMemory → JDBC 영속화로 전환. 저장소 구현({@code JdbcChatMemoryRepository})은
 * {@code spring-ai-starter-model-chat-memory-repository-jdbc} 가 기존 PostgreSQL {@code DataSource} 위에
 * 자동 구성하므로, 여기서는 주입받은 {@link ChatMemoryRepository} 를 윈도우로 감싸기만 한다.
 * 스키마(SPRING_AI_CHAT_MEMORY)는 {@code spring.ai.chat.memory.repository.jdbc.initialize-schema} 로 생성.
 */
@Configuration
public class ChatMemoryConfig {

    private static final int MAX_MESSAGES = 20;

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(MAX_MESSAGES)
                .build();
    }
}
