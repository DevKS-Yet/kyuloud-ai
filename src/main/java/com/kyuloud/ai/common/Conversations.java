package com.kyuloud.ai.common;

import org.springframework.util.StringUtils;

/**
 * Phase 8a — 대화 세션 식별자 처리 공통 헬퍼.
 *
 * <p>{@code conversationId} 가 비어 있을 때 기본값으로 해석하는 동일 로직(상수 + {@code hasText} 분기)이
 * 채팅/RAG/에이전트 서비스에 흩어져 있던 것을 한곳으로 모은다. 동작은 기존과 같다(빈 값 → {@code "default"}).
 */
public final class Conversations {

    /** {@code conversationId} 미지정 시 사용할 기본 세션 식별자. */
    public static final String DEFAULT_CONVERSATION_ID = "default";

    private Conversations() {
    }

    /** {@code conversationId} 가 비어 있으면 {@link #DEFAULT_CONVERSATION_ID} 로 해석한다. */
    public static String resolve(String conversationId) {
        return StringUtils.hasText(conversationId) ? conversationId : DEFAULT_CONVERSATION_ID;
    }
}
