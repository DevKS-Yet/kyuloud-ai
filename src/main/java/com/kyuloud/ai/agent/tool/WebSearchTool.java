package com.kyuloud.ai.agent.tool;

import com.kyuloud.ai.agent.service.ToolCallTracker;
import com.kyuloud.ai.config.SearchProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Phase 3d-2 — 웹 검색 도구 (SearXNG 자체 호스팅).
 *
 * <p>SearXNG({@code docker compose up -d}) 의 JSON 검색 API를 호출한다.
 * 외부 상용 API 키·요금이 불필요하고 로컬 인프라에서 완결된다.
 * 엔드포인트: {@code GET /search?q={query}&format=json&language=ko&categories=general}
 */
@Slf4j
@Component
public class WebSearchTool {

    private final RestClient restClient;
    private final SearchProperties searchProperties;
    private final ToolCallTracker toolCallTracker;

    public WebSearchTool(SearchProperties searchProperties, ToolCallTracker toolCallTracker) {
        this.searchProperties = searchProperties;
        this.toolCallTracker = toolCallTracker;
        this.restClient = RestClient.builder()
                .baseUrl(searchProperties.getSearxngUrl())
                .build();
    }

    @Tool(description = "인터넷에서 최신 정보를 검색한다. "
            + "학습 데이터에 없는 최신 정보·시사·날씨·이슈 등 실시간 정보가 필요한 질문에 호출한다. "
            + "문서 지식베이스 검색이 아닌 '웹 전체'를 대상으로 한다.")
    public String searchWeb(
            @ToolParam(description = "검색할 질의(자연어 키워드/문장, 가능하면 영어가 검색 품질에 유리)") String query) {
        toolCallTracker.record("searchWeb");
        log.debug("WebSearchTool: query='{}'", query);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.get()
                    .uri(uri -> uri.path("/search")
                            .queryParam("q", query)
                            .queryParam("format", "json")
                            .queryParam("language", "ko-KR")
                            .queryParam("categories", "general")
                            .build())
                    .retrieve()
                    .body(Map.class);

            if (response == null) {
                return "검색 결과 없음.";
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");
            if (results == null || results.isEmpty()) {
                return "검색 결과 없음: '" + query + "'에 대한 결과를 찾지 못했습니다.";
            }

            List<Map<String, Object>> top = results.stream()
                    .limit(searchProperties.getMaxResults())
                    .toList();

            log.debug("WebSearchTool: '{}' → {}건 반환", query, top.size());

            return IntStream.range(0, top.size())
                    .mapToObj(i -> formatResult(i + 1, top.get(i)))
                    .reduce((a, b) -> a + "\n\n" + b)
                    .orElse("");

        } catch (RestClientException e) {
            log.warn("WebSearchTool: SearXNG 호출 실패 — {}", e.getMessage());
            return "웹 검색 실패: SearXNG 서비스에 연결할 수 없습니다. (docker compose up -d 확인 필요)";
        }
    }

    private String formatResult(int index, Map<String, Object> result) {
        String title = (String) result.getOrDefault("title", "(제목 없음)");
        String url = (String) result.getOrDefault("url", "");
        String content = (String) result.getOrDefault("content", "");
        return "[" + index + "] " + title + "\n" + url + "\n" + content;
    }
}
