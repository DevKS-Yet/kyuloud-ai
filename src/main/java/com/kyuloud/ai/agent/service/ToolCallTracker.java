package com.kyuloud.ai.agent.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 3c — 도구 호출 추적기 / Phase 3d-1 — 스트리밍 안전성 보강.
 *
 * <p>각 {@code @Tool} 메서드가 호출 시 자신을 기록하고, {@code AgentService} 가 응답에
 * 호출된 도구 목록(tool-call trace)을 담아 반환한다.
 *
 * <p>구현은 {@link ThreadLocal} 기반 싱글톤이다. {@code @RequestScope} 와 달리 요청 스레드가
 * 바인딩되지 않은 곳(스트리밍 시 reactor 스레드 등)에서 {@link #record(String)} 가 호출돼도
 * <em>예외 없이</em> 해당 스레드 로컬에 기록만 하고 끝난다(읽지 않으면 무해). blocking 경로에서는
 * {@code .call()} 과 도구 실행·결과 읽기가 동일 스레드라 정상적으로 추적된다. 요청 종료 시
 * {@link #reset()} 으로 스레드 로컬을 정리해 풀링 스레드 간 누수를 방지한다.
 */
@Component
public class ToolCallTracker {

    private final ThreadLocal<List<String>> calledTools = ThreadLocal.withInitial(ArrayList::new);

    public void record(String toolName) {
        calledTools.get().add(toolName);
    }

    public List<String> getCalledTools() {
        return List.copyOf(calledTools.get());
    }

    public void reset() {
        calledTools.remove();
    }
}
