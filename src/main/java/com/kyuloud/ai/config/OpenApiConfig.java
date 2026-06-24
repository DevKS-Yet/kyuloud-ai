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
                        .description("RAG 문서 Q&A / 채팅 AI 모듈 통합 테스트용 API 문서")
                        .version("v0.0.1")
                        .license(new License().name("Apache 2.0")));
    }
}
