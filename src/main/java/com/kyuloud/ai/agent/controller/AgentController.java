package com.kyuloud.ai.agent.controller;

import com.kyuloud.ai.agent.dto.AgentResponse;
import com.kyuloud.ai.agent.dto.LoopResponse;
import com.kyuloud.ai.agent.dto.PlanResponse;
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

    /**
     * Phase 3d-4 — Plan-and-Execute. 복잡한 multi-step 요청을 계획→단계별 실행→합성으로 분리 수행하고,
     * 계획·단계별 결과·최종 답변을 함께 반환한다.
     */
    @PostMapping("/plan")
    public ApiResponse<PlanResponse> plan(@Valid @RequestBody ChatRequest request) {
        PlanResponse response = agentService.planAndExecute(request.conversationId(), request.message());
        return ApiResponse.ok(response);
    }

    /**
     * Phase 5 — 평가 기반 에이전트 루프. 질문 파악·계획 → (행동 → 평가 → 추가 행동)* → 답변 으로,
     * 행동마다 충분성을 평가해 부족하면 추가 검색/다른 도구로 보완한 뒤 답한다. 계획·회차별 행동/평가 기록을 함께 반환.
     */
    @PostMapping("/loop")
    public ApiResponse<LoopResponse> loop(@Valid @RequestBody ChatRequest request) {
        LoopResponse response = agentService.loop(request.conversationId(), request.message());
        return ApiResponse.ok(response);
    }
}
