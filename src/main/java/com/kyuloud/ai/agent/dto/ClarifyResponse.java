package com.kyuloud.ai.agent.dto;

import java.util.List;

/**
 * Phase 5b — 명확화 엔드포인트 응답.
 *
 * <p>{@code needsClarification=true} 이면 클라이언트는 {@code questions}(선택지 포함)를 사용자에게 보여주고,
 * 사용자가 답한 내용을 더해 같은 {@code conversationId} 로 답변 엔드포인트(chat/plan/loop)를 다시 호출한다.
 * {@code false} 이면 되물을 필요 없이 바로 답변 엔드포인트를 호출하면 된다.
 *
 * @param request            원본 사용자 질문
 * @param needsClarification 사용자에게 되물어야 하는지 여부
 * @param questions          되물을 질문 목록(없으면 빈 목록)
 */
public record ClarifyResponse(
        String request,
        boolean needsClarification,
        List<ClarifyingQuestion> questions
) {
}
