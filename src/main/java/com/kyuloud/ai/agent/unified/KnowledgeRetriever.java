package com.kyuloud.ai.agent.unified;

import com.kyuloud.ai.config.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.IntStream;

/**
 * Phase 6 — 문서 지식베이스 직접 검색기(RAG 단일화).
 *
 * <p>누적 구현은 RAG 를 advisor({@code /rag/chat})와 도구({@code RagSearchTool}) 두 경로로 노출했다.
 * 통합 흐름에서는 retriever 를 DIRECT/Orchestrator 가 <em>코드에서 직접</em> 호출해 일원화한다(도구 노출 불필요).
 *
 * <p>검색 시점 결정(#1=c): "검색이 필요한가"를 LLM 으로 판단하지 않고 <b>항상 1회 검색</b>하되
 * {@code similarityThreshold} 로 무관 결과를 걸러, 빈 결과면 컨텍스트를 주입하지 않는다(결정적·단순).
 * "지금 몇시야" 류는 threshold 에서 자연히 탈락한다.
 */
@Slf4j
@Component
public class KnowledgeRetriever {

    private static final int SNIPPET_MAX_LENGTH = 500;

    private final VectorStore vectorStore;
    private final RagProperties ragProperties;

    public KnowledgeRetriever(VectorStore vectorStore, RagProperties ragProperties) {
        this.vectorStore = vectorStore;
        this.ragProperties = ragProperties;
    }

    /**
     * 질의로 1회 검색해 관련 문서를 컨텍스트 텍스트로 만든다. 관련 문서가 없으면 빈 문자열을 반환한다(주입 생략 신호).
     */
    public String retrieveContext(String query) {
        List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(ragProperties.getTopK())
                .similarityThreshold(ragProperties.getSimilarityThreshold())
                .build());

        if (documents == null || documents.isEmpty()) {
            log.debug("KnowledgeRetriever: '{}' 관련 문서 없음 → 컨텍스트 미주입", query);
            return "";
        }
        log.debug("KnowledgeRetriever: '{}' → {}건 주입", query, documents.size());
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
