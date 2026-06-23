package com.kyuloud.ai.rag.controller;

import com.kyuloud.ai.common.dto.ApiResponse;
import com.kyuloud.ai.rag.dto.IngestRequest;
import com.kyuloud.ai.rag.dto.IngestResponse;
import com.kyuloud.ai.rag.ingest.DocumentIngestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 2a — 문서 적재 API.
 */
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentIngestService ingestService;

    @PostMapping
    public ApiResponse<IngestResponse> ingest(@Valid @RequestBody IngestRequest request) {
        int chunks = ingestService.ingest(request.source(), request.text());
        return ApiResponse.ok(new IngestResponse(request.source(), chunks));
    }
}
