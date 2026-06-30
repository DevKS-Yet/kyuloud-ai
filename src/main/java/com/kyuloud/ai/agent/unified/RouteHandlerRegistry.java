package com.kyuloud.ai.agent.unified;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 8c — 라우트 전략 레지스트리(Strategy + Factory/Registry). Spring 이 모든 {@link RouteHandler} 빈을
 * 모아 {@link RouteStrategy} enum 키 맵으로 색인한다 — {@code AgentTool} 마커 자동수집과 같은 패턴이다.
 *
 * <p>새 라우트(예: 미래의 SUMMARIZE/TRANSLATE)는 {@link RouteHandler} 빈 하나만 추가하면 자동 등록된다
 * (호출부 수정 불필요, open-closed). 한 전략에 핸들러가 둘 이상이거나 DIRECT 핸들러가 없으면(폴백 경로 보장
 * 실패) 기동 시점에 즉시 실패시켜 잘못된 배선을 일찍 드러낸다(fail-fast).
 */
@Slf4j
@Component
public class RouteHandlerRegistry {

    private final Map<RouteStrategy, RouteHandler> handlers;

    public RouteHandlerRegistry(List<RouteHandler> handlerBeans) {
        Map<RouteStrategy, RouteHandler> map = new EnumMap<>(RouteStrategy.class);
        for (RouteHandler handler : handlerBeans) {
            RouteHandler previous = map.put(handler.strategy(), handler);
            if (previous != null) {
                throw new IllegalStateException("RouteStrategy " + handler.strategy()
                        + " 에 핸들러가 둘 이상입니다: " + previous.getClass().getSimpleName()
                        + " ↔ " + handler.getClass().getSimpleName());
            }
        }
        if (!map.containsKey(RouteStrategy.DIRECT)) {
            throw new IllegalStateException("DIRECT 핸들러가 없습니다 — 폴백 경로를 보장할 수 없습니다");
        }
        this.handlers = map;
        log.debug("RouteHandlerRegistry: {}개 라우트 핸들러 등록 — {}", map.size(), map.keySet());
    }

    /** 전략에 해당하는 핸들러를 돌려준다. 미등록 전략은 안전 기본값 {@link RouteStrategy#DIRECT} 로 폴백한다. */
    public RouteHandler get(RouteStrategy strategy) {
        RouteHandler handler = handlers.get(strategy);
        if (handler == null) {
            log.warn("RouteHandlerRegistry: '{}' 핸들러 없음 → DIRECT 폴백", strategy);
            return handlers.get(RouteStrategy.DIRECT);
        }
        return handler;
    }

    /** CLARIFY 과민 폴백 등에서 명시적으로 쓰는 DIRECT 핸들러. */
    public RouteHandler direct() {
        return handlers.get(RouteStrategy.DIRECT);
    }
}
