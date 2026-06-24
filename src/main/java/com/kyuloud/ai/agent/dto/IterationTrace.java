package com.kyuloud.ai.agent.dto;

/**
 * Phase 5 — 평가 루프의 한 반복(행동→평가) 기록.
 *
 * @param iteration   반복 회차(1부터)
 * @param action      이 회차에 수행한 행동(계획 단계 또는 평가자가 제안한 다음 행동)
 * @param observation 행동 실행 결과(도구 호출 포함 에이전트 출력)
 * @param sufficient  이 회차 평가 결과 — 누적 근거로 충분히 답할 수 있는지
 * @param missing     부족하다고 평가된 경우 그 설명(충분하면 빈 문자열)
 */
public record IterationTrace(
        int iteration,
        String action,
        String observation,
        boolean sufficient,
        String missing
) {
}
