package com.kyuloud.ai.agent.unified;

import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.stereotype.Component;

/**
 * Phase 8b — per-request 생성 옵션 팩토리(Factory 패턴).
 *
 * <p>요청 단위 {@link OllamaChatOptions} 생성을 한곳으로 모은다. 기존에는 동일한
 * {@code OllamaChatOptions.builder().model(ctx.model())} 가 DIRECT 답변({@code UnifiedAgentService.direct})과
 * RESEARCH 워커({@code OrchestratorService.runWorker})에 인라인으로 흩어져 있었다.
 *
 * <p>지금은 사용자가 고른 모델(Phase 7, D4)만 적용하지만, 추후 요청별 생성 파라미터
 * (temperature·num_ctx·top_p·seed 등)를 줄 때 <b>호출부 수정 없이</b> 이 팩토리만 확장하면 된다
 * — Phase 7의 "모델만 선택"을 "생성 파라미터까지 선택"으로 넓힐 자연스러운 이음새다(로컬 전용 원칙 불변).
 * 미래 파라미터는 {@link AgentContext} 에 실어 여기서 읽으면 호출부가 그대로 유지된다.
 */
@Component
public class ChatOptionsFactory {

    /**
     * 요청 컨텍스트로부터 Ollama 생성 옵션 빌더를 만든다. DIRECT 답변·RESEARCH 워커가 공유한다
     * (D4: 선택 모델 적용 지점). Spring AI 2.0 의 {@code ChatClient...options(B)} 가 빌더를 받아
     * 호출 시점에 build 하므로, 빌더를 반환한다(추후 파라미터 확장도 이 빌더 체인에 이어 붙이면 된다).
     *
     * @param ctx 요청 컨텍스트(선택 모델 등 per-request 정보 보유)
     * @return 이 요청에 적용할 {@link OllamaChatOptions.Builder}
     */
    public OllamaChatOptions.Builder forRequest(AgentContext ctx) {
        return OllamaChatOptions.builder()
                .model(ctx.model());
    }
}
