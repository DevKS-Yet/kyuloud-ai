package com.kyuloud.ai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Phase 2b — RAG 검색 파라미터.
 * {@code application*.yaml} 의 {@code kyuloud.rag.*} 로 외부화한다.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "kyuloud.rag")
public class RagProperties {

    /** 검색해 컨텍스트로 주입할 최대 문서(청크) 수. */
    private int topK = 4;

    /** 검색 결과로 채택할 최소 유사도(0.0~1.0). */
    private double similarityThreshold = 0.5;
}
