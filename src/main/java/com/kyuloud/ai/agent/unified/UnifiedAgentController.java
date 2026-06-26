package com.kyuloud.ai.agent.unified;

import com.kyuloud.ai.chat.dto.ChatRequest;
import com.kyuloud.ai.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Phase 6 — 통합 에이전트 단일 진입점({@code POST /api/agent}). <b>권장 경로.</b>
 *
 * <p>기존 분산 엔드포인트({@code /api/agent/chat|plan|loop|clarify}, {@code /api/rag/chat})를 클린 재구현으로
 * 대체한다. 내부에서 Router 가 전략을 정해 분기한다:
 * <ul>
 *   <li><b>DIRECT</b> — 단발 답변(+ 항상 저비용 RAG 1회 검색·threshold 필터, 도구 사용).</li>
 *   <li><b>RESEARCH</b> — Orchestrator-workers: 동적 분해 → 독립 워커 병렬·의존 워커 순차 → Evaluator 보강 → 합성.</li>
 *   <li><b>CLARIFY</b> — 핵심 정보가 부족하면 되묻는 질문(+선택지)을 반환(같은 conversationId 로 재호출 시 맥락 유지).</li>
 * </ul>
 * RAG 는 도구로 이중 노출하지 않고 retriever 를 직접 호출해 단일화했다(#1=c).
 *
 * <p>Phase 7 — 요청의 {@code model}(선택)로 DIRECT 답변·RESEARCH 워커의 Ollama 생성 모델을 고를 수 있다
 * (내부 Router/분해/합성/평가는 기본 모델 고정). 사용 가능한 모델은 {@code GET /api/agent/models} 로 조회한다.
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class UnifiedAgentController {

    private final UnifiedAgentService unifiedAgentService;
    private final ModelCatalog modelCatalog;

    @PostMapping
    public ApiResponse<UnifiedAgentResponse> agent(@Valid @RequestBody ChatRequest request) {
        UnifiedAgentResponse response = unifiedAgentService.agent(
                request.conversationId(), request.message(), request.model());
        return ApiResponse.ok(response);
    }

    /** Phase 7 — 사용자가 고를 수 있는 Ollama 모델 목록(allow-list). 프론트 모델 선택 UI 용. */
    @GetMapping("/models")
    public ApiResponse<List<ModelInfo>> models() {
        return ApiResponse.ok(modelCatalog.list());
    }
}
