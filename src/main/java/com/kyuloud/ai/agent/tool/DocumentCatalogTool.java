package com.kyuloud.ai.agent.tool;

import com.kyuloud.ai.agent.service.ToolCallTracker;
import com.kyuloud.ai.domain.entity.DocumentMetadata;
import com.kyuloud.ai.domain.repository.DocumentMetadataRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Phase 3c — 적재 문서 카탈로그 도구.
 *
 * <p>지식베이스에 <em>어떤</em> 문서가 적재되어 있는지(이름/청크 수/적재 시각)를 조회한다.
 * 내용 검색은 {@link RagSearchTool} 이 담당하고, 이 도구는 "무엇이 있는지"의 메타 질의를 맡는다.
 */
@Slf4j
@Component
public class DocumentCatalogTool {

    private final DocumentMetadataRepository metadataRepository;
    private final ToolCallTracker toolCallTracker;

    public DocumentCatalogTool(DocumentMetadataRepository metadataRepository,
                               ToolCallTracker toolCallTracker) {
        this.metadataRepository = metadataRepository;
        this.toolCallTracker = toolCallTracker;
    }

    @Tool(description = "적재된 문서 목록과 메타데이터(문서명/청크 수/적재 시각)를 조회한다. "
            + "어떤 문서가 있는지, 무엇을 적재했는지 등 문서 목록에 대한 질문에 호출한다.")
    public String listDocuments() {
        toolCallTracker.record("listDocuments");
        List<DocumentMetadata> documents = metadataRepository.findAllByOrderByIdDesc();
        if (documents.isEmpty()) {
            return "적재된 문서가 없습니다.";
        }
        return documents.stream()
                .map(d -> "- " + d.getSource() + " (청크 " + d.getChunks() + "개, 적재 " + d.getCreatedAt() + ")")
                .collect(Collectors.joining("\n"));
    }
}
