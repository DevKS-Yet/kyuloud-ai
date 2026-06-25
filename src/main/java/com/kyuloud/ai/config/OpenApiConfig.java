package com.kyuloud.ai.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger UI / OpenAPI 문서 메타데이터 구성.
 * 통합 테스트 편의를 위해 모든 REST 엔드포인트를 {@code /swagger-ui.html} 에서 노출한다.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI kyuloudOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("kyuloud-ai API")
                        .description("""
                                RAG 문서 Q&A / 채팅 AI 모듈 통합 테스트용 API 문서.

                                권장 진입점: POST /api/agent (통합 에이전트 — Router→DIRECT/RESEARCH/CLARIFY).
                                /api/agent/chat|plan|loop|clarify, /api/rag/chat 은 Phase 6 에서 통합되어 deprecated
                                (검증·비교용 유지). 문서 적재·목록은 /api/documents 가 담당한다.""")
                        .version("v0.0.1")
                        .license(new License().name("Apache 2.0")));
    }
}
