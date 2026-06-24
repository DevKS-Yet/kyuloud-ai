package com.kyuloud.ai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Phase 3d-2 — 웹 검색(SearXNG) 파라미터.
 * {@code kyuloud.search.*} 로 외부화한다.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "kyuloud.search")
public class SearchProperties {

    /** SearXNG 기본 URL (예: http://localhost:8888) */
    private String searxngUrl = "http://localhost:8888";

    /** 반환할 최대 검색 결과 수 */
    private int maxResults = 5;
}
