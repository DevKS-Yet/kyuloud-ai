package com.kyuloud.ai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 7 — 사용자가 런타임에 고를 수 있는 로컬 Ollama 모델 목록(allow-list).
 * {@code kyuloud.ollama.*} 로 외부화한다.
 *
 * <p>외부 프로바이더는 도입하지 않는다(로컬 LLM = 보안·비용). 모든 모델은 같은 Ollama 서버에 있고,
 * 운영자가 {@code ollama pull} 로 미리 설치해 둔 모델만 등록한다(자동 다운로드 안 함). allow-list 밖
 * 모델 요청은 거부한다. VRAM 한계를 고려해 목록은 작게 유지한다.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "kyuloud.ollama")
public class OllamaModelProperties {

    /** 요청에 model 미지정 시 사용할 기본 모델(allow-list 에 없어도 항상 허용된다). */
    private String defaultModel = "gemma4:e4b";

    /** 선택 가능한 모델 목록. */
    private List<ModelEntry> models = new ArrayList<>();

    /**
     * allow-list 엔 있으나 Ollama 에 실제로 설치되지 않은 모델을 요청했을 때의 처리(Phase 7b).
     * 설치 여부는 {@code OllamaApi.listModels()} 로 확인하며, 확인 자체가 불가하면(목록 조회 실패) 그대로 진행한다.
     */
    private OnUnavailable onUnavailable = OnUnavailable.DEFAULT;

    /** 미설치 모델 처리 정책. */
    public enum OnUnavailable {
        /** 기본 모델로 폴백(요청은 성공, 응답 executedModel 이 기본 모델로 표시됨). */
        DEFAULT,
        /** 400 으로 거부(MODEL_NOT_INSTALLED). */
        ERROR
    }

    /** 단일 모델 항목(이름 + 표시명). */
    @Getter
    @Setter
    public static class ModelEntry {

        /** Ollama 모델 태그(예: {@code gemma4:e4b}, {@code qwen2.5:7b}). 호출 시 그대로 쓴다. */
        private String name;

        /** UI 표시명(예: "Gemma 4 (e4b, 기본)"). 없으면 name 을 쓴다. */
        private String displayName;
    }
}
