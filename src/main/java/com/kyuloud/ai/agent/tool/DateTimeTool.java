package com.kyuloud.ai.agent.tool;

import com.kyuloud.ai.agent.service.ToolCallTracker;
import com.kyuloud.ai.agent.unified.CallTracer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Phase 3a — 현재 날짜/시각 도구.
 *
 * <p>외부 의존이 없어 tool-calling 동작 검증에 사용한다. LLM은 "지금 몇 시야?",
 * "오늘 무슨 요일이야?" 같은 질문에 이 도구를 호출해 실제 시각 기반으로 답한다.
 */
@Slf4j
@Component
public class DateTimeTool implements AgentTool {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd(EEE) HH:mm:ss");

    private final ToolCallTracker toolCallTracker;

    public DateTimeTool(ToolCallTracker toolCallTracker) {
        this.toolCallTracker = toolCallTracker;
    }

    @Tool(description = "현재 날짜와 시각을 반환한다. 사용자가 오늘 날짜, 요일, 현재 시각을 물을 때 호출한다.")
    public String getCurrentDateTime(ToolContext toolContext) {
        toolCallTracker.record("getCurrentDateTime");
        CallTracer.recordTo(toolContext, "getCurrentDateTime");
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        String result = now.format(FORMATTER) + " (" + now.getZone() + ")";
        log.debug("DateTimeTool 호출: {}", result);
        return result;
    }
}
