package com.kyuloud.ai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Phase 6 — 통합 에이전트 요청 단위 예산/정지조건. {@code kyuloud.agent.budget.*} 로 외부화한다.
 * 무한루프·비용 폭주를 막는 상한이며, 초과 시 best-effort 합성으로 부분 답변한다(#4).
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "kyuloud.agent.budget")
public class AgentBudgetProperties {

    /** 한 요청에서 허용하는 최대 LLM 호출 수(router + 워커 + 합성 등 합산). */
    private int maxLlmCalls = 8;

    /** 한 요청의 총 소요시간 상한(ms). 경과 시 더 진행하지 않고 지금까지 근거로 답한다. */
    private long timeoutMillis = 60_000L;
}
