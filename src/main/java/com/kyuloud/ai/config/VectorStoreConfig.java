package com.kyuloud.ai.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 벡터 저장소 구성.
 *
 * <p>Phase 2b 기준 기본 동작은 {@code spring-ai-starter-vector-store-pgvector} 의
 * 자동 구성이 제공하는 pgvector 기반 {@code PgVectorStore} 빈을 사용한다
 * (PostgreSQL + pgvector 필요, {@code docker-compose.yml} 참고).
 *
 * <p>{@code poc} 프로파일에서는 외부 인프라 없이 검증할 수 있도록 인메모리
 * {@link SimpleVectorStore} 를 사용한다(재시작 시 휘발). 이 빈이 존재하면
 * pgvector 자동 구성은 {@code @ConditionalOnMissingBean} 으로 비활성화된다.
 */
@Configuration
public class VectorStoreConfig {

    @Bean
    @Profile("poc")
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }
}
