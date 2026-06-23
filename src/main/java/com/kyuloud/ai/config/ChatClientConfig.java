package com.kyuloud.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 공통 ChatClient 빈 구성.
 * Phase 1a에서는 기본 시스템 프롬프트만 지정한다.
 * (메모리/RAG/Tool 등 Advisor 는 이후 단계에서 추가)
 */
@Configuration
public class ChatClientConfig {

    private static final String DEFAULT_SYSTEM_PROMPT = """
            당신은 kyuloud-ai의 친절한 AI 어시스턴트입니다.
            한국어로 명확하고 간결하게 답변하세요.
            모르는 내용은 추측하지 말고 모른다고 답하세요.
            """;

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, ChatMemory chatMemory) {
        return builder
                .defaultSystem(DEFAULT_SYSTEM_PROMPT)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }
}
