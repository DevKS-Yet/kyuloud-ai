package com.kyuloud.ai.rag.ingest;

import com.kyuloud.ai.domain.entity.DocumentMetadata;
import com.kyuloud.ai.domain.repository.DocumentMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;

/**
 * Phase 2b — 문서 적재(ETL) 서비스.
 * 텍스트/파일을 청킹 → 임베딩 → pgvector VectorStore 에 적재하고,
 * 적재 메타데이터를 RDB 에 기록한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIngestService {

    private static final String DEFAULT_SOURCE = "inline";

    private final VectorStore vectorStore;
    private final DocumentMetadataRepository metadataRepository;
    private final TokenTextSplitter splitter = TokenTextSplitter.builder().build();

    /** 텍스트 본문 직접 적재. */
    public int ingest(String source, String text) {
        return store(source, List.of(new Document(text)));
    }

    /** 파일 적재 (Tika 로 PDF/Word/HTML/Text 등 파싱). */
    public int ingestFile(String source, Resource resource) {
        List<Document> documents = new TikaDocumentReader(resource).get();
        return store(source, documents);
    }

    private int store(String source, List<Document> documents) {
        String src = StringUtils.hasText(source) ? source : DEFAULT_SOURCE;
        documents.forEach(doc -> doc.getMetadata().put("source", src));

        List<Document> chunks = splitter.apply(documents);
        vectorStore.add(chunks);

        metadataRepository.save(DocumentMetadata.builder()
                .source(src)
                .chunks(chunks.size())
                .createdAt(Instant.now())
                .build());

        log.debug("ingested source={} chunks={}", src, chunks.size());
        return chunks.size();
    }
}
