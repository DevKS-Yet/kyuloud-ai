package com.kyuloud.ai.agent.controller;

import com.kyuloud.ai.agent.dto.AgentResponse;
import com.kyuloud.ai.agent.dto.ClarifyResponse;
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
 * Phase 3~5 — Agent(도구 + 메모리) 채팅 API.
 *
 * @deprecated Phase 6 의 통합 에이전트 {@code POST /api/agent}({@link com.kyuloud.ai.agent.unified.UnifiedAgentController})
 * 로 대체됨. 이 컨트롤러의 {@code chat/stream/plan/loop/clarify} 는 Phase 6 가 Router→(DIRECT/RESEARCH/CLARIFY)
 * 로 통합했다. 검증·비교용으로 당분간 유지하며, 제거는 신규 경로 실호출 검증 후 별도 결정한다(PLAN 6f).
 */
@Deprecated
@RestController
@RequestMapping("/api/agent/chat")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;

    /** @deprecated {@code POST /api/agent}(DIRECT 경로)로 대체. */
    @Deprecated
    @PostMapping
    public ApiResponse<AgentResponse> chat(@Valid @RequestBody ChatRequest request) {
        AgentResponse response = agentService.chat(request.conversationId(), request.message());
        return ApiResponse.ok(response);
    }

    /**
     * Phase 3d-1 — 도구를 호출하며 최종 답변을 토큰 단위로 스트리밍(SSE).
     *
     * @deprecated 통합 에이전트는 우선 blocking({@code POST /api/agent})으로 제공한다(#7, 스트리밍은 추후).
     */
    @Deprecated
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@Valid @RequestBody ChatRequest request) {
        return agentService.stream(request.conversationId(), request.message());
    }

    /**
     * Phase 3d-4 — Plan-and-Execute. 복잡한 multi-step 요청을 계획→단계별 실행→합성으로 분리 수행하고,
     * 계획·단계별 결과·최종 답변을 함께 반환한다.
     *
     * @deprecated {@code POST /api/agent}(RESEARCH 경로 — Orchestrator-workers)로 대체.
     */
    @Deprecated
    @PostMapping("/plan")
    public ApiResponse<PlanResponse> plan(@Valid @RequestBody ChatRequest request) {
        PlanResponse response = agentService.planAndExecute(request.conversationId(), request.message());
        return ApiResponse.ok(response);
    }

    /**
     * Phase 5 — 평가 기반 에이전트 루프. 질문 파악·계획 → (행동 → 평가 → 추가 행동)* → 답변 으로,
     * 행동마다 충분성을 평가해 부족하면 추가 검색/다른 도구로 보완한 뒤 답한다. 계획·회차별 행동/평가 기록을 함께 반환.
     *
     * @deprecated {@code POST /api/agent}(RESEARCH 경로의 Evaluator-optimizer 보강)로 대체.
     */
    @Deprecated
    @PostMapping("/loop")
    public ApiResponse<LoopResponse> loop(@Valid @RequestBody ChatRequest request) {
        LoopResponse response = agentService.loop(request.conversationId(), request.message());
        return ApiResponse.ok(response);
    }

    /**
     * Phase 5b — 명확화. 답변하기 전에, 질문에 제대로 답하려면 사용자에게 되물어야 할 정보가 있는지 판단한다.
     * 되물을 게 있으면 선택지 포함 질문을 반환(클라가 사용자에게 보여줌), 없으면 바로 답변 엔드포인트를 호출하면 된다.
     *
     * @deprecated {@code POST /api/agent}(CLARIFY 경로)로 대체.
     */
    @Deprecated
    @PostMapping("/clarify")
    public ApiResponse<ClarifyResponse> clarify(@Valid @RequestBody ChatRequest request) {
        ClarifyResponse response = agentService.clarify(request.conversationId(), request.message());
        return ApiResponse.ok(response);
    }
}
