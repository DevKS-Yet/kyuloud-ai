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

    /**
     * RESEARCH 경로의 Evaluator-optimizer 보강 반복 상한(6e). 충분성 평가가 부족(missing)이라 판단하면 보강
     * 워커를 추가 투입하는 라운드 수의 최대치이며, 이와 별개로 {@link #maxLlmCalls} 예산도 함께 적용된다(둘 중
     * 먼저 닿는 쪽에서 멈춤). 0 이면 보강 없이 1회 평가만 한다(충분/부족 무관하게 보강 안 함).
     */
    private int maxReinforcements = 2;
}
