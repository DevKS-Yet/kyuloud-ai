package com.kyuloud.ai.rag.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 문서 적재 요청 (Phase 2a PoC — 텍스트 본문 직접 적재).
 *
 * @param source 출처/문서명(선택). 메타데이터로 저장된다.
 * @param text   적재할 본문 텍스트
 */
public record IngestRequest(
        String source,

        @NotBlank(message = "text는 비어 있을 수 없습니다.")
        String text
) {
}
