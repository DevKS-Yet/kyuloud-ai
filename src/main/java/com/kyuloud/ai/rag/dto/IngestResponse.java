package com.kyuloud.ai.rag.dto;

/**
 * 문서 적재 결과.
 *
 * @param source 적재된 문서 출처
 * @param chunks 생성된 청크 수
 */
public record IngestResponse(
        String source,
        int chunks
) {
}
