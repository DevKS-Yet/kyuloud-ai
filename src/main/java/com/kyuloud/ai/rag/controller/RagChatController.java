package com.kyuloud.ai.rag.controller;

import com.kyuloud.ai.chat.dto.ChatRequest;
import com.kyuloud.ai.common.dto.ApiResponse;
import com.kyuloud.ai.rag.dto.RagChatResponse;
import com.kyuloud.ai.rag.retrieval.RagChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 2b — RAG 질의 API (답변 + 출처 반환).
 *
 * @deprecated Phase 6 에서 RAG 를 단일화했다(#1=c). 통합 에이전트 {@code POST /api/agent} 의 DIRECT·워커 경로가
 * {@code KnowledgeRetriever} 로 문서를 <em>직접</em> 검색(advisor·tool 이중 노출 폐기)하므로 별도 RAG 질의
 * 엔드포인트가 필요 없다. 검증·비교용으로 당분간 유지하며, 제거는 별도 결정한다(PLAN 6f). 문서 적재·목록은
 * {@code /api/documents}({@code DocumentController}) 가 계속 담당한다.
 */
@Deprecated
@RestController
@RequestMapping("/api/rag/chat")
@RequiredArgsConstructor
public class RagChatController {

    private final RagChatService ragChatService;

    /** @deprecated {@code POST /api/agent}(RAG 단일화)로 대체. */
    @Deprecated
    @PostMapping
    public ApiResponse<RagChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        RagChatResponse response = ragChatService.chat(request.conversationId(), request.message());
        return ApiResponse.ok(response);
    }
}
