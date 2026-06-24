package com.kyuloud.ai.agent.tool;

import com.kyuloud.ai.agent.service.ToolCallTracker;
import com.kyuloud.ai.config.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.IntStream;

/**
 * Phase 3b — RAG 문서 검색 도구.
 *
 * <p>Phase 2b 의 RAG는 advisor 가 자동으로 검색을 주입했지만, Agent 에서는 이를 하나의
 * {@link Tool 도구}로 노출해 LLM 이 <em>필요하다고 판단할 때만</em> 능동적으로 검색하게 한다.
 * 검색 파라미터(topK / similarityThreshold)는 {@link RagProperties} 를 공유한다.
 */
@Slf4j
@Component
public class RagSearchTool {

    private static final int SNIPPET_MAX_LENGTH = 500;

    private final VectorStore vectorStore;
    private final RagProperties ragProperties;
    private final ToolCallTracker toolCallTracker;

    public RagSearchTool(VectorStore vectorStore, RagProperties ragProperties,
                         ToolCallTracker toolCallTracker) {
        this.vectorStore = vectorStore;
        this.ragProperties = ragProperties;
        this.toolCallTracker = toolCallTracker;
    }

    @Tool(description = "적재된 문서 지식베이스에서 질문과 관련된 내용을 검색한다. "
            + "문서·자료에 근거가 필요한 질문에 답하기 전에 호출하고, 반환된 출처를 근거로 답하라.")
    public String searchDocuments(
            @ToolParam(description = "검색할 질의(자연어 키워드/문장)") String query) {
        toolCallTracker.record("searchDocuments");
        List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(ragProperties.getTopK())
                .similarityThreshold(ragProperties.getSimilarityThreshold())
                .build());

        if (documents == null || documents.isEmpty()) {
            log.debug("RagSearchTool: '{}' 관련 문서 없음", query);
            return "검색 결과: 관련 문서를 찾을 수 없습니다.";
        }

        log.debug("RagSearchTool: '{}' → {}건 검색", query, documents.size());
        return IntStream.range(0, documents.size())
                .mapToObj(i -> formatDocument(i + 1, documents.get(i)))
                .reduce((a, b) -> a + "\n\n" + b)
                .orElse("");
    }

    private String formatDocument(int index, Document document) {
        Object source = document.getMetadata().get("source");
        String text = document.getText();
        String snippet = text == null ? ""
                : text.length() > SNIPPET_MAX_LENGTH ? text.substring(0, SNIPPET_MAX_LENGTH) + "…" : text;
        return "[" + index + "] 출처: " + (source == null ? "(미상)" : source) + "\n" + snippet;
    }
}
