package com.kyuloud.ai.agent.unified;

import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Phase 6 — 수집형 도구 호출 추적기.
 *
 * <p>기존 {@code ToolCallTracker}(ThreadLocal) 는 스트리밍/병렬 워커에서 호출 스레드가 바뀌면 기록이 깨진다.
 * 이 추적기는 요청별 인스턴스를 {@link AgentContext} 가 들고 다니고, Spring AI 의 {@link ToolContext}
 * (요청별 맵, {@code .toolContext(Map)} 로 주입)를 통해 {@code @Tool} 메서드에 전달된다. 각 도구는
 * {@link #recordTo(ToolContext, String)} 로 자신을 기록하므로, 어느 스레드에서 실행되든(가상스레드 병렬 포함)
 * 같은 인스턴스에 안전하게 누적된다.
 *
 * <p>내부 저장소는 {@link CopyOnWriteArrayList} 라 병렬 워커의 동시 기록에도 스레드 안전하다.
 */
public class CallTracer {

    /** {@code .toolContext(Map)} 에 추적기를 넣을 때 쓰는 키. {@code @Tool} 메서드가 같은 키로 꺼낸다. */
    public static final String TOOL_CONTEXT_KEY = "kyuloud.callTracer";

    private final List<String> calledTools = new CopyOnWriteArrayList<>();

    public void record(String toolName) {
        calledTools.add(toolName);
    }

    public List<String> getCalledTools() {
        return List.copyOf(calledTools);
    }

    /** 이 추적기를 {@link ToolContext} 페이로드로 감싼다. {@code .toolContext(tracer.asToolContext())}. */
    public Map<String, Object> asToolContext() {
        return Map.of(TOOL_CONTEXT_KEY, this);
    }

    /**
     * {@code @Tool} 메서드에서 호출하는 정적 헬퍼. {@link ToolContext} 에 추적기가 실려 있으면 도구 이름을 기록한다.
     * 추적기가 없거나(구 엔드포인트 호출 등) context 가 비어 있으면 아무것도 하지 않아, 도구를 양쪽 경로에서 안전하게 공유한다.
     */
    public static void recordTo(ToolContext toolContext, String toolName) {
        if (toolContext == null) {
            return;
        }
        Object tracer = toolContext.getContext().get(TOOL_CONTEXT_KEY);
        if (tracer instanceof CallTracer callTracer) {
            callTracer.record(toolName);
        }
    }
}
