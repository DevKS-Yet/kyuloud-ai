package com.kyuloud.ai.agent.service;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 3c — 요청 단위 도구 호출 추적기.
 *
 * <p>각 {@code @Tool} 메서드가 호출 시 자신을 기록하고, {@code AgentService} 가 응답에
 * 호출된 도구 목록(tool-call trace)을 담아 반환한다. {@code @RequestScope} 이므로 동시
 * 요청 간 섞이지 않으며, 싱글톤 도구에는 스코프 프록시로 주입된다(도구 호출은 {@code .call()}
 * 과 동일 요청 스레드에서 동기 실행되므로 같은 요청 인스턴스로 해석된다).
 */
@Component
@RequestScope
public class ToolCallTracker {

    private final List<String> calledTools = new ArrayList<>();

    public void record(String toolName) {
        calledTools.add(toolName);
    }

    public List<String> getCalledTools() {
        return List.copyOf(calledTools);
    }
}
