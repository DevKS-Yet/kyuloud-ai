package com.kyuloud.ai.agent.controller;

import com.kyuloud.ai.agent.dto.AgentResponse;
import com.kyuloud.ai.agent.service.AgentService;
import com.kyuloud.ai.chat.dto.ChatRequest;
import com.kyuloud.ai.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

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

    /**
     * Phase 3d-1 — 도구를 호출하며 최종 답변을 토큰 단위로 스트리밍(SSE).
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@Valid @RequestBody ChatRequest request) {
        return agentService.stream(request.conversationId(), request.message());
    }
}
