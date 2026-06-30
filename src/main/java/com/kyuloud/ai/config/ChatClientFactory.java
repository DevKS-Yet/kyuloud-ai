package com.kyuloud.ai.config;

import com.kyuloud.ai.common.advisor.GuardrailAdvisor;
import com.kyuloud.ai.common.advisor.LoggingAdvisor;
import com.kyuloud.ai.common.advisor.MetricsAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Phase 8b — ChatClient 생성 팩토리(Factory 패턴). 역할별 ChatClient 가 공유하는 advisor 체인
 * (Guardrail → Logging → Metrics) 배선을 한 곳에 모은다. 기존에는 네 빈이 같은
 * {@code .defaultAdvisors(guardrail, logging, metrics)} 를 각자 반복 배선했다(#6 관측 일관성).
 *
 * <p>이제 새 내부-역할 클라이언트는 {@link #internalRoleClient()} 한 줄로 추가되고, 메모리·시스템 프롬프트가
 * 필요한 클라이언트는 {@link #baseBuilder()} 에 덧붙여 build 한다. advisor 순서·구성을 바꿔야 할 때도
 * 이 팩토리만 고치면 모든 역할에 일관 반영된다.
 *
 * <p><b>왜 {@link ObjectProvider}</b>: Spring AI 자동구성의 {@code ChatClient.Builder} 는 <em>prototype</em>
 * 스코프라 주입 지점마다 새 인스턴스여야 한다. 싱글톤 팩토리가 빌더를 필드로 잡아 재사용하면 모든 클라이언트가
 * 같은(가변) 빌더를 공유하게 되므로, 생성 시점마다 {@code getObject()} 로 새 빌더를 받는다.
 */
@Component
public class ChatClientFactory {

    private final ObjectProvider<ChatClient.Builder> builderProvider;
    private final GuardrailAdvisor guardrailAdvisor;
    private final LoggingAdvisor loggingAdvisor;
    private final MetricsAdvisor metricsAdvisor;

    public ChatClientFactory(ObjectProvider<ChatClient.Builder> builderProvider,
                             GuardrailAdvisor guardrailAdvisor,
                             LoggingAdvisor loggingAdvisor,
                             MetricsAdvisor metricsAdvisor) {
        this.builderProvider = builderProvider;
        this.guardrailAdvisor = guardrailAdvisor;
        this.loggingAdvisor = loggingAdvisor;
        this.metricsAdvisor = metricsAdvisor;
    }

    /**
     * 공통 advisor 체인(Guardrail → Logging → Metrics)을 건 <b>새</b> 빌더를 돌려준다. 호출부가 시스템 프롬프트·
     * 대화 메모리 등 역할별 요소를 덧붙여 {@code build()} 한다. {@code defaultAdvisors} 는 누적이므로 이후 추가
     * advisor 는 이 셋 뒤에 이어진다(순서 보존).
     */
    public ChatClient.Builder baseBuilder() {
        return builderProvider.getObject()
                .defaultAdvisors(guardrailAdvisor, loggingAdvisor, metricsAdvisor);
    }

    /**
     * 내부 역할(planner/router/worker) 공용 클라이언트 — 도구·대화 메모리 없이 공통 advisor 체인만 적용한다.
     * 새 내부 역할(예: 요약 전용)도 이 메서드 호출 한 줄로 추가한다.
     */
    public ChatClient internalRoleClient() {
        return baseBuilder().build();
    }
}
