package com.kyuloud.ai.config;

import com.kyuloud.ai.common.advisor.GuardrailAdvisor;
import com.kyuloud.ai.common.advisor.LoggingAdvisor;
import com.kyuloud.ai.common.advisor.MetricsAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.Resource;

/**
 * 공통 ChatClient 빈 구성.
 * 시스템 프롬프트는 외부 리소스(prompts/system-chat.st)에서 주입하고,
 * 대화 메모리 advisor를 기본 적용한다. (RAG/Tool 등은 이후 단계에서 추가)
 *
 * <p>Phase 4a — 모든 ChatClient 호출을 {@link LoggingAdvisor} 로 감싸 요청·응답·토큰·지연을 로깅한다.
 */
@Configuration
public class ChatClientConfig {

    @Value("classpath:prompts/system-chat.st")
    private Resource systemPrompt;

    @Bean
    @Primary
    public ChatClient chatClient(ChatClient.Builder builder, ChatMemory chatMemory,
                                 GuardrailAdvisor guardrailAdvisor, LoggingAdvisor loggingAdvisor,
                                 MetricsAdvisor metricsAdvisor) {
        return builder
                .defaultSystem(systemPrompt)
                .defaultAdvisors(guardrailAdvisor, loggingAdvisor, metricsAdvisor,
                        MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    /**
     * Phase 3d-4 — Planner 전용 ChatClient.
     *
     * <p>계획 수립·결과 합성에만 쓰이므로 <em>도구·대화 메모리 advisor 없이</em> 구성한다(시스템 프롬프트는
     * 호출 시점에 planner/synthesis 용으로 각각 지정). 메모리가 없어 사용자 대화 맥락을 오염시키지 않고,
     * 도구가 없어 계획 단계에서 섣불리 도구를 호출하지 않는다(실제 도구 실행은 {@code chatClient} 가 담당).
     */
    @Bean
    public ChatClient plannerChatClient(ChatClient.Builder builder, GuardrailAdvisor guardrailAdvisor,
                                        LoggingAdvisor loggingAdvisor, MetricsAdvisor metricsAdvisor) {
        return builder
                .defaultAdvisors(guardrailAdvisor, loggingAdvisor, metricsAdvisor)
                .build();
    }

    /**
     * Phase 6 — 통합 에이전트 Router 전용 ChatClient.
     *
     * <p>진입 분류기(전략 enum 구조화 출력)만 담당한다. <em>도구·대화 메모리 advisor 없이</em> 구성해 분류 외
     * 부작용을 막는다. 진입 클라이언트이므로 Guardrail(입력 차단/PII)·Logging·Metrics 를 적용한다(#6) —
     * 특히 Guardrail 은 사용자 입력을 가장 먼저 통과시키는 이 클라이언트에 필수.
     */
    @Bean
    public ChatClient routerChatClient(ChatClient.Builder builder, GuardrailAdvisor guardrailAdvisor,
                                       LoggingAdvisor loggingAdvisor, MetricsAdvisor metricsAdvisor) {
        return builder
                .defaultAdvisors(guardrailAdvisor, loggingAdvisor, metricsAdvisor)
                .build();
    }

    /**
     * Phase 6 — 통합 에이전트 워커/DIRECT 답변 전용 ChatClient.
     *
     * <p>도구를 갖춘 답변 생성에 쓴다. <em>대화 메모리 advisor 는 두지 않는다</em> — 통합 흐름은 대화 맥락을
     * 읽어와 명시적으로 메시지로 주입하고 최종 한 턴만 기록하기 때문(메모리 seed/clear 트릭 폐기). 도구는
     * 호출 시점에 {@code .tools(...)} 로 주입한다. Guardrail·Logging·Metrics 를 적용한다(#6).
     */
    @Bean
    public ChatClient workerChatClient(ChatClient.Builder builder, GuardrailAdvisor guardrailAdvisor,
                                       LoggingAdvisor loggingAdvisor, MetricsAdvisor metricsAdvisor) {
        return builder
                .defaultAdvisors(guardrailAdvisor, loggingAdvisor, metricsAdvisor)
                .build();
    }
}
