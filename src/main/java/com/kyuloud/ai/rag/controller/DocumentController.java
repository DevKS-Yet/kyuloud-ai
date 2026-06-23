package com.kyuloud.ai.rag.controller;

import com.kyuloud.ai.common.dto.ApiResponse;
import com.kyuloud.ai.domain.repository.DocumentMetadataRepository;
import com.kyuloud.ai.rag.dto.DocumentSummary;
import com.kyuloud.ai.rag.dto.IngestRequest;
import com.kyuloud.ai.rag.dto.IngestResponse;
import com.kyuloud.ai.rag.ingest.DocumentIngestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Phase 2b — 문서 적재/조회 API.
 */
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentIngestService ingestService;
    private final DocumentMetadataRepository metadataRepository;

    /** 텍스트 본문 적재. */
    @PostMapping
    public ApiResponse<IngestResponse> ingest(@Valid @RequestBody IngestRequest request) {
        int chunks = ingestService.ingest(request.source(), request.text());
        return ApiResponse.ok(new IngestResponse(request.source(), chunks));
    }

    /** 파일 업로드 적재 (PDF/Word/HTML/Text 등). */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<IngestResponse> ingestFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "source", required = false) String source) {
        String src = StringUtils.hasText(source) ? source : file.getOriginalFilename();
        int chunks = ingestService.ingestFile(src, file.getResource());
        return ApiResponse.ok(new IngestResponse(src, chunks));
    }

    /** 적재 문서 목록. */
    @GetMapping
    public ApiResponse<List<DocumentSummary>> list() {
        List<DocumentSummary> documents = metadataRepository.findAllByOrderByIdDesc().stream()
                .map(m -> new DocumentSummary(m.getId(), m.getSource(), m.getChunks(), m.getCreatedAt()))
                .toList();
        return ApiResponse.ok(documents);
    }
}
