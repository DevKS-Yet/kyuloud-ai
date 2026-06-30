package com.kyuloud.ai.common;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

/**
 * Phase 8a — 대화 메모리 기록 공통 헬퍼.
 *
 * <p>사용자 대화에 '원 질문 → 최종 답변' 한 턴만 기록하는 동일 로직({@code recordTurn})이 통합 에이전트와
 * (구) 에이전트 서비스에 똑같이 중복돼 있던 것을 한곳으로 모은다. 동작은 기존과 같다
 * ({@code UserMessage} + {@code AssistantMessage} 한 쌍을 추가).
 */
public final class ChatMemories {

    private ChatMemories() {
    }

    /** 사용자 대화({@code cid})에 '사용자 질문 → 어시스턴트 답변' 한 턴을 기록한다(중간 산출물 제외). */
    public static void recordTurn(ChatMemory chatMemory, String cid, String userMessage, String reply) {
        chatMemory.add(cid, List.of(new UserMessage(userMessage), new AssistantMessage(reply)));
    }
}
