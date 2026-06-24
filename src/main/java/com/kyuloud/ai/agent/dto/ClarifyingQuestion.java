package com.kyuloud.ai.agent.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * Phase 5b — 에이전트가 사용자에게 되묻는 한 개의 명확화 질문.
 *
 * <p>구조화 출력(JSON)으로 LLM 이 직접 생성하므로 필드 설명을 함께 제공한다. 클라이언트는 {@code options}
 * 가 있으면 선택지로 보여주고, 비어 있으면 자유 입력을 받는다.
 *
 * @param question 사용자에게 되물을 질문
 * @param options  빠르게 고를 수 있는 선택지(2~4개). 자유 입력이 적절하면 빈 목록.
 */
public record ClarifyingQuestion(
        @JsonPropertyDescription("사용자에게 되물을 질문")
        String question,

        @JsonPropertyDescription("사용자가 빠르게 고를 수 있는 선택지 2~4개. 자유 입력이 더 적절하면 빈 목록")
        List<String> options
) {
}
