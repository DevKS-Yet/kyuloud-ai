package com.kyuloud.ai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Phase 5 — 평가 기반 에이전트 루프 설정.
 * {@code application*.yaml} 의 {@code kyuloud.agent.loop.*} 로 외부화한다.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "kyuloud.agent.loop")
public class AgentLoopProperties {

    /** 행동→평가 루프의 최대 반복 횟수(비용·지연 상한이자 무한루프 방지). */
    private int maxIterations = 3;
}
