package com.kyuloud.ai.common.advisor;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * Phase 4b — Micrometer 메트릭 Advisor.
 *
 * <p>모든 {@code ChatClient} 호출의 지연(latency)과 토큰 사용량을 Micrometer 미터로 기록해
 * actuator(`/actuator/metrics`, `/actuator/prometheus`)로 노출한다. {@link LoggingAdvisor}(로깅) 와
 * 관심사를 분리한 별도 advisor 로, 둘 다 전체 호출을 바깥에서 감싼다.
 *
 * <ul>
 *   <li>{@code kyuloud.ai.chat.latency} (Timer) — 호출 지연. 태그: {@code model}.</li>
 *   <li>{@code kyuloud.ai.chat.tokens} (DistributionSummary) — 토큰 수. 태그: {@code model}, {@code type=prompt|completion|total}.</li>
 * </ul>
 */
@Component
public class MetricsAdvisor implements BaseAdvisor {

    private static final String START_TIME_KEY = "kyuloud.metrics.startNanos";
    private static final String LATENCY_METRIC = "kyuloud.ai.chat.latency";
    private static final String TOKENS_METRIC = "kyuloud.ai.chat.tokens";
    private static final String UNKNOWN_MODEL = "unknown";

    private final MeterRegistry meterRegistry;

    public MetricsAdvisor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain advisorChain) {
        return request.mutate()
                .context(START_TIME_KEY, System.nanoTime())
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain advisorChain) {
        ChatResponse chatResponse = response.chatResponse();
        String model = modelTag(chatResponse);

        recordLatency(response, model);
        recordTokens(chatResponse, model);
        return response;
    }

    /**
     * 로깅 advisor 와 함께 메모리/RAG/도구보다 바깥에서 전체 호출 시간을 포함해 측정한다.
     */
    @Override
    public int getOrder() {
        return Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER - 90;
    }

    private void recordLatency(ChatClientResponse response, String model) {
        Object start = response.context().get(START_TIME_KEY);
        if (start instanceof Long startNanos) {
            meterRegistry.timer(LATENCY_METRIC, "model", model)
                    .record(Duration.ofNanos(System.nanoTime() - startNanos));
        }
    }

    private void recordTokens(ChatResponse chatResponse, String model) {
        if (chatResponse == null || chatResponse.getMetadata() == null
                || chatResponse.getMetadata().getUsage() == null) {
            return;
        }
        Usage usage = chatResponse.getMetadata().getUsage();
        record(model, "prompt", usage.getPromptTokens());
        record(model, "completion", usage.getCompletionTokens());
        record(model, "total", usage.getTotalTokens());
    }

    private void record(String model, String type, Integer tokens) {
        if (tokens != null) {
            meterRegistry.summary(TOKENS_METRIC, "model", model, "type", type).record(tokens);
        }
    }

    private String modelTag(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getMetadata() == null) {
            return UNKNOWN_MODEL;
        }
        String model = chatResponse.getMetadata().getModel();
        return StringUtils.hasText(model) ? model : UNKNOWN_MODEL;
    }
}
