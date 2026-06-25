package com.kyuloud.ai.agent.unified;

/**
 * Phase 6 — 요청 단위 예산/정지조건. 무한루프·비용 폭주를 막기 위해 LLM 호출 수와 총 소요시간 상한을
 * {@link AgentContext} 가 강제한다. 상한 초과는 예외가 아니라 "더 진행하지 말라"는 신호로 쓰여,
 * 지금까지 모은 근거로 best-effort 합성하게 한다(#4).
 *
 * <p>도구 호출 상한은 {@link CallTracer} 의 누적 길이로 별도 점검할 수 있다(이 클래스는 LLM·시간만 책임).
 */
public class Budget {

    private final int maxLlmCalls;
    private final long deadlineNanos;
    private int llmCalls;

    public Budget(int maxLlmCalls, long timeoutMillis) {
        this.maxLlmCalls = maxLlmCalls;
        this.deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000L;
    }

    /** LLM 호출 1회를 예약한다. 예산이 남아 있으면 카운트를 올리고 {@code true}, 소진됐으면 {@code false}. */
    public synchronized boolean tryConsumeLlmCall() {
        if (isExhausted()) {
            return false;
        }
        llmCalls++;
        return true;
    }

    /** 예산 소진 여부(호출 수 상한 도달 또는 타임아웃 경과). */
    public synchronized boolean isExhausted() {
        return llmCalls >= maxLlmCalls || System.nanoTime() >= deadlineNanos;
    }

    public synchronized int usedLlmCalls() {
        return llmCalls;
    }

    public int maxLlmCalls() {
        return maxLlmCalls;
    }
}
