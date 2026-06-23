package com.kyuloud.ai.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phase 2a — 인메모리 벡터 저장소(PoC).
 * Ollama 임베딩 모델(nomic-embed-text)로 임베딩하며, 애플리케이션 메모리에 보관한다.
 * (재시작 시 휘발 → Phase 2b에서 pgvector로 교체 예정)
 */
@Configuration
public class VectorStoreConfig {

    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }
}
