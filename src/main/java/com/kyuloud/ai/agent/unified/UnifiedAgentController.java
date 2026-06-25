package com.kyuloud.ai.agent.unified;

import com.kyuloud.ai.chat.dto.ChatRequest;
import com.kyuloud.ai.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 6 — 통합 에이전트 단일 진입점.
 *
 * <p>기존 분산 엔드포인트({@code /api/agent/chat|plan|loop|clarify})를 클린 재구현으로 대체하는 단일 진입점.
 * 내부에서 Router 가 전략(DIRECT/RESEARCH/CLARIFY)을 정해 분기한다. 6a 에서는 DIRECT 경로만 연결됨.
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class UnifiedAgentController {

    private final UnifiedAgentService unifiedAgentService;

    @PostMapping
    public ApiResponse<UnifiedAgentResponse> agent(@Valid @RequestBody ChatRequest request) {
        UnifiedAgentResponse response = unifiedAgentService.agent(request.conversationId(), request.message());
        return ApiResponse.ok(response);
    }
}
