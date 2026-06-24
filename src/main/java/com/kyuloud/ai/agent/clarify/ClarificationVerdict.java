package com.kyuloud.ai.agent.clarify;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.kyuloud.ai.agent.dto.ClarifyingQuestion;

import java.util.List;

/**
 * Phase 5b — 명확화 판단 결과(구조화 출력 대상).
 *
 * <p>{@link ClarificationService} 가 "질문에 답하기 위해 사용자에게 되물어야 하는가"를 판단해 LLM 구조화
 * 출력(JSON)으로 생성한다. 되물어야 하면 꼭 필요한 질문만 담는다.
 *
 * @param needsClarification 핵심 정보가 부족해 사용자에게 되물어야 하면 true
 * @param questions          되물을 질문 목록(needsClarification=true 일 때). 아니면 빈 목록.
 */
@JsonClassDescription("질문에 답하기 위해 사용자에게 추가 정보를 되물어야 하는지에 대한 판단")
public record ClarificationVerdict(
        @JsonPropertyDescription("핵심 정보가 부족하거나 모호해 사용자에게 되물어야 하면 true, 충분하면 false")
        boolean needsClarification,

        @JsonPropertyDescription("되물을 질문 목록. needsClarification 이 false 이면 빈 목록")
        List<ClarifyingQuestion> questions
) {

    /** 되묻지 않음(=충분하니 그대로 답변 진행). 판단 실패 시 안전 폴백으로도 사용. */
    public static ClarificationVerdict noClarification() {
        return new ClarificationVerdict(false, List.of());
    }
}
