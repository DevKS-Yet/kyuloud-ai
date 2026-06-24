package com.kyuloud.ai.agent.clarify;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Phase 5b — 명확화(Clarification) 판단기.
 *
 * <p>RAG(부족한 정보를 문서에서 채움)와 반대로, 답하기 위해 부족한 정보를 <em>사용자에게 되물어</em> 채우는
 * Human-in-the-loop 패턴. 사용자 질문과 대화 맥락을 받아 되물어야 할 핵심 정보가 있는지 판단한다.
 * 도구·메모리 없는 보조 ChatClient({@code plannerChatClient} 재사용)로 구조화 출력({@link ClarificationVerdict})을 만든다.
 * 판단 실패 시 되묻지 않음으로 폴백해 흐름을 막지 않는다.
 */
@Slf4j
@Service
public class ClarificationService {

    private final ChatClient auxiliaryChatClient;

    @Value("classpath:prompts/system-clarify.st")
    private Resource clarifySystemPrompt;

    public ClarificationService(@Qualifier("plannerChatClient") ChatClient auxiliaryChatClient) {
        this.auxiliaryChatClient = auxiliaryChatClient;
    }

    public ClarificationVerdict assess(String question, String conversationContext) {
        String user = "대화 맥락:\n" + (StringUtils.hasText(conversationContext) ? conversationContext : "(없음)")
                + "\n\n사용자 질문:\n" + question
                + "\n\n이 질문에 제대로 답하려면 사용자에게 되물어야 할 핵심 정보가 있는지 판단하세요.";
        try {
            ClarificationVerdict verdict = auxiliaryChatClient.prompt()
                    .system(clarifySystemPrompt)
                    .user(user)
                    .call()
                    .entity(ClarificationVerdict.class);

            if (verdict == null) {
                return ClarificationVerdict.noClarification();
            }
            List<?> questions = verdict.questions();
            // 되묻겠다면서 질문이 비어 있으면 모순 → 되묻지 않음으로 정규화.
            if (verdict.needsClarification() && (questions == null || questions.isEmpty())) {
                return ClarificationVerdict.noClarification();
            }
            if (questions == null) {
                return new ClarificationVerdict(verdict.needsClarification(), List.of());
            }
            log.debug("Clarification: needsClarification={}, questions={}",
                    verdict.needsClarification(), questions.size());
            return verdict;
        } catch (Exception e) {
            log.warn("Clarification 판단 실패 → 되묻지 않음: {}", e.getMessage());
            return ClarificationVerdict.noClarification();
        }
    }
}
