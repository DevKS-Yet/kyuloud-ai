package com.kyuloud.ai.chat.controller;

import com.kyuloud.ai.chat.dto.ChatRequest;
import com.kyuloud.ai.chat.dto.ChatResponse;
import com.kyuloud.ai.chat.service.ChatService;
import com.kyuloud.ai.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 1a — 단발 채팅 API.
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping
    public ApiResponse<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        String reply = chatService.chat(request.message());
        return ApiResponse.ok(new ChatResponse(reply));
    }
}
