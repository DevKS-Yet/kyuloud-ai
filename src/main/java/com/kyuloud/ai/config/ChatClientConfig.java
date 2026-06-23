package com.kyuloud.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

/**
 * 공통 ChatClient 빈 구성.
 * 시스템 프롬프트는 외부 리소스(prompts/system-chat.st)에서 주입하고,
 * 대화 메모리 advisor를 기본 적용한다. (RAG/Tool 등은 이후 단계에서 추가)
 */
@Configuration
public class ChatClientConfig {

    @Value("classpath:prompts/system-chat.st")
    private Resource systemPrompt;

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, ChatMemory chatMemory) {
        return builder
                .defaultSystem(systemPrompt)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }
}
