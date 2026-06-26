package com.kyuloud.ai.agent.unified;

import com.kyuloud.ai.common.exception.BusinessException;
import com.kyuloud.ai.config.OllamaModelProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Phase 7 — Ollama 모델 allow-list 검증/조회.
 *
 * <p>요청이 보낸 모델 이름을 허용 목록(설정의 {@code kyuloud.ollama.models} + 기본 모델)으로 검증하고,
 * 미지정이면 기본 모델로 해석한다. 목록 밖 모델은 {@link BusinessException}(400)으로 거부한다 —
 * 오타·미설치·과도한 VRAM 모델을 호출 전에 차단한다.
 */
@Slf4j
@Component
public class ModelCatalog {

    private final OllamaModelProperties properties;
    /** 허용 모델 이름(기본 모델 포함, 입력 순서 보존). */
    private final Set<String> allowed;

    public ModelCatalog(OllamaModelProperties properties) {
        this.properties = properties;
        Set<String> names = new LinkedHashSet<>();
        // 기본 모델은 목록에 없어도 항상 허용(하위호환·폴백 안전).
        if (StringUtils.hasText(properties.getDefaultModel())) {
            names.add(properties.getDefaultModel().trim());
        }
        for (OllamaModelProperties.ModelEntry entry : properties.getModels()) {
            if (entry != null && StringUtils.hasText(entry.getName())) {
                names.add(entry.getName().trim());
            }
        }
        this.allowed = Set.copyOf(names);
        log.info("ModelCatalog: 허용 모델 {}개 {} (기본: {})", allowed.size(), allowed, properties.getDefaultModel());
    }

    /**
     * 요청 모델을 검증·해석한다. 비어 있으면 기본 모델, allow-list 안이면 그대로, 밖이면 400.
     *
     * @param requested 요청이 보낸 model(선택). null/blank 가능
     * @return 실제 사용할 모델 이름
     * @throws BusinessException 허용되지 않은 모델일 때(HTTP 400)
     */
    public String resolve(String requested) {
        if (!StringUtils.hasText(requested)) {
            return properties.getDefaultModel();
        }
        String model = requested.trim();
        if (!allowed.contains(model)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "UNKNOWN_MODEL",
                    "허용되지 않은 모델입니다: '" + model + "'. 사용 가능한 모델: " + String.join(", ", allowed));
        }
        return model;
    }

    /** 기본 모델 이름. */
    public String defaultModel() {
        return properties.getDefaultModel();
    }

    /** 선택 가능한 모델 목록(표시명·기본 여부 포함). */
    public List<ModelInfo> list() {
        String defaultModel = properties.getDefaultModel();
        List<ModelInfo> result = new ArrayList<>();
        boolean defaultListed = false;
        for (OllamaModelProperties.ModelEntry entry : properties.getModels()) {
            if (entry == null || !StringUtils.hasText(entry.getName())) {
                continue;
            }
            String name = entry.getName().trim();
            String display = StringUtils.hasText(entry.getDisplayName()) ? entry.getDisplayName() : name;
            boolean isDefault = name.equals(defaultModel);
            defaultListed |= isDefault;
            result.add(new ModelInfo(name, display, isDefault));
        }
        // 기본 모델이 목록에 명시되지 않았으면 맨 앞에 보강(항상 허용되므로 노출).
        if (!defaultListed && StringUtils.hasText(defaultModel)) {
            result.add(0, new ModelInfo(defaultModel, defaultModel, true));
        }
        return result;
    }
}
