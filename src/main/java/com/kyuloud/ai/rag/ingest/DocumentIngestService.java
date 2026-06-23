package com.kyuloud.ai.rag.ingest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 2a — 문서 적재(ETL) 서비스.
 * 텍스트를 청킹 → 임베딩 → VectorStore 에 적재한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIngestService {

    private static final String DEFAULT_SOURCE = "inline";

    private final VectorStore vectorStore;
    private final TokenTextSplitter splitter = TokenTextSplitter.builder().build();

    public int ingest(String source, String text) {
        String src = StringUtils.hasText(source) ? source : DEFAULT_SOURCE;

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", src);

        List<Document> chunks = splitter.apply(List.of(new Document(text, metadata)));
        vectorStore.add(chunks);

        log.debug("ingested source={} chunks={}", src, chunks.size());
        return chunks.size();
    }
}
