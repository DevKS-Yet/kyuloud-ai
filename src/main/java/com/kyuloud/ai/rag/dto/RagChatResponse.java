package com.kyuloud.ai.rag.dto;

import java.util.List;

/**
 * Phase 2b — RAG 응답 (답변 + 근거 출처).
 *
 * @param reply     LLM 답변 텍스트
 * @param citations 답변 근거가 된 검색 문서 출처 목록
 */
public record RagChatResponse(
        String reply,
        List<Citation> citations
) {

    /**
     * 검색된 근거 문서.
     *
     * @param source  문서 출처/이름
     * @param score   유사도 점수(0.0~1.0, 없으면 null)
     * @param snippet 본문 발췌
     */
    public record Citation(
            String source,
            Double score,
            String snippet
    ) {
    }
}
