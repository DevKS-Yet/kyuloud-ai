package com.kyuloud.ai.agent.controller;

import com.kyuloud.ai.agent.dto.AgentResponse;
import com.kyuloud.ai.agent.service.AgentService;
import com.kyuloud.ai.chat.dto.ChatRequest;
import com.kyuloud.ai.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 3 — Agent(도구 + 메모리) 채팅 API.
 */
@RestController
@RequestMapping("/api/agent/chat")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;

    @PostMapping
    public ApiResponse<AgentResponse> chat(@Valid @RequestBody ChatRequest request) {
        AgentResponse response = agentService.chat(request.conversationId(), request.message());
        return ApiResponse.ok(response);
    }
}
