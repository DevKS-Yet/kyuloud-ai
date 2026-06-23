package com.kyuloud.ai.rag.controller;

import com.kyuloud.ai.chat.dto.ChatRequest;
import com.kyuloud.ai.chat.dto.ChatResponse;
import com.kyuloud.ai.common.dto.ApiResponse;
import com.kyuloud.ai.rag.retrieval.RagChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 2a — RAG 질의 API.
 */
@RestController
@RequestMapping("/api/rag/chat")
@RequiredArgsConstructor
public class RagChatController {

    private final RagChatService ragChatService;

    @PostMapping
    public ApiResponse<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        String reply = ragChatService.chat(request.conversationId(), request.message());
        return ApiResponse.ok(new ChatResponse(reply));
    }
}
