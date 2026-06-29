package com.kyuloud.ai.common;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

import java.util.List;

/**
 * Phase 8a — 대화 기록 렌더링 공통 헬퍼.
 *
 * <p>대화 메모리({@code List<Message>})를 역할 라벨(사용자/어시스턴트/시스템) + 내용 텍스트로 펼치는
 * 동일 로직({@code renderHistory} + {@code roleLabel})이 통합 에이전트와 (구) 에이전트 서비스에 똑같이
 * 중복돼 있던 것을 한곳으로 모은다. 동작은 기존과 같다(빈/널 → 빈 문자열, 한 줄당 "역할: 내용").
 */
public final class ConversationHistory {

    private ConversationHistory() {
    }

    /** 대화 기록을 분류·명확화 판단용 컨텍스트 텍스트(역할 라벨 + 내용)로 렌더링한다. */
    public static String render(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Message m : history) {
            sb.append(roleLabel(m.getMessageType())).append(": ").append(m.getText()).append("\n");
        }
        return sb.toString();
    }

    private static String roleLabel(MessageType type) {
        return switch (type) {
            case USER -> "사용자";
            case ASSISTANT -> "어시스턴트";
            case SYSTEM -> "시스템";
            default -> type.getValue();
        };
    }
}
