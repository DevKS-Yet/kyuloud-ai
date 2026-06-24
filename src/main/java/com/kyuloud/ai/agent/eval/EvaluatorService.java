package com.kyuloud.ai.agent.eval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * Phase 5 — 평가자(Evaluator).
 *
 * <p>원래 질문과 지금까지 수집한 근거를 받아, 그 근거만으로 충분·정확히 답할 수 있는지 평가한다.
 * 도구·메모리가 없는 보조 추론 ChatClient({@code plannerChatClient} 재사용)로 구조화 출력({@link EvaluationVerdict})을 만든다.
 * 평가에 실패하면 무한 루프를 막기 위해 "충분"으로 간주하는 안전 폴백을 반환한다.
 */
@Slf4j
@Service
public class EvaluatorService {

    private final ChatClient auxiliaryChatClient;

    @Value("classpath:prompts/system-evaluator.st")
    private Resource evaluatorSystemPrompt;

    public EvaluatorService(@Qualifier("plannerChatClient") ChatClient auxiliaryChatClient) {
        this.auxiliaryChatClient = auxiliaryChatClient;
    }

    public EvaluationVerdict evaluate(String question, String accumulatedEvidence) {
        String user = "원래 질문:\n" + question
                + "\n\n지금까지 수집한 근거:\n" + accumulatedEvidence
                + "\n\n이 근거만으로 질문에 충분하고 정확하게 답할 수 있는지 평가하세요.";
        try {
            EvaluationVerdict verdict = auxiliaryChatClient.prompt()
                    .system(evaluatorSystemPrompt)
                    .user(user)
                    .call()
                    .entity(EvaluationVerdict.class);

            if (verdict == null) {
                log.debug("Evaluator: 빈 평가 결과 → 충분 폴백");
                return EvaluationVerdict.sufficientFallback();
            }
            log.debug("Evaluator: sufficient={}, missing={}", verdict.sufficient(), verdict.missing());
            return verdict;
        } catch (Exception e) {
            log.warn("Evaluator: 평가 실패 → 충분 폴백(루프 종료): {}", e.getMessage());
            return EvaluationVerdict.sufficientFallback();
        }
    }
}
