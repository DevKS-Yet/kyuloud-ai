package com.kyuloud.ai.rag.dto;

import java.time.Instant;

/**
 * 적재 문서 목록 항목 (Phase 2b).
 *
 * @param id        메타데이터 식별자
 * @param source    문서 출처/이름
 * @param chunks    생성된 청크 수
 * @param createdAt 적재 시각
 */
public record DocumentSummary(
        Long id,
        String source,
        int chunks,
        Instant createdAt
) {
}
