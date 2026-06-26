package com.kyuloud.ai.agent.unified;

import com.kyuloud.ai.common.exception.BusinessException;
import com.kyuloud.ai.config.OllamaModelProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
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

    /** 설치 모델 목록 캐시 TTL — 너무 자주 Ollama 에 묻지 않되, pull 직후 곧 반영되게 짧게. */
    private static final long INSTALLED_TTL_NANOS = Duration.ofSeconds(30).toNanos();

    private final OllamaModelProperties properties;
    /** 허용 모델 이름(기본 모델 포함, 입력 순서 보존). */
    private final Set<String> allowed;
    /** 설치 모델 조회용(graceful) — Ollama 자동구성이 없으면 부재할 수 있어 ObjectProvider 로 보관. */
    private final ObjectProvider<OllamaApi> ollamaApiProvider;

    /** 설치 모델 캐시(검증 성공 시 채워짐). 검증 불가면 {@code installedVerified=false}. */
    private volatile Set<String> installedCache = Set.of();
    private volatile boolean installedVerified = false;
    private volatile long installedExpiresAtNanos = 0L;

    public ModelCatalog(OllamaModelProperties properties, ObjectProvider<OllamaApi> ollamaApiProvider) {
        this.properties = properties;
        this.ollamaApiProvider = ollamaApiProvider;
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
        log.info("ModelCatalog: 허용 모델 {}개 {} (기본: {}, on-unavailable: {})",
                allowed.size(), allowed, properties.getDefaultModel(), properties.getOnUnavailable());
    }

    /**
     * 요청 모델을 검증·해석한다. 비어 있으면 기본 모델, allow-list 밖이면 400, allow-list 안이지만 Ollama 에
     * 미설치면 {@code on-unavailable} 정책대로(기본 모델 폴백 또는 400) 처리한다. 설치 여부 확인이 불가하면
     * (목록 조회 실패) 그대로 진행한다(graceful).
     *
     * @param requested 요청이 보낸 model(선택). null/blank 가능
     * @return 실제 사용할 모델 이름
     * @throws BusinessException 허용되지 않은 모델(UNKNOWN_MODEL) 또는 미설치+ERROR 정책(MODEL_NOT_INSTALLED)
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
        return resolveInstalled(model);
    }

    /** 설치 여부 확인 후 {@code on-unavailable} 정책 적용. 확인 불가면 요청 모델 그대로. */
    private String resolveInstalled(String model) {
        Optional<Set<String>> installed = installedModels();
        if (installed.isEmpty() || installed.get().contains(model)) {
            return model;   // 설치됨이 확인되었거나, 확인 불가(graceful 진행)
        }
        // 미설치가 확인됨
        if (properties.getOnUnavailable() == OllamaModelProperties.OnUnavailable.ERROR) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MODEL_NOT_INSTALLED",
                    "모델이 Ollama 에 설치되어 있지 않습니다: '" + model + "'. `ollama pull " + model + "` 후 다시 시도하세요.");
        }
        log.warn("ModelCatalog: 모델 '{}' 미설치 → 기본 모델 '{}' 로 폴백", model, properties.getDefaultModel());
        return properties.getDefaultModel();
    }

    /**
     * 설치 모델 이름 집합을 캐시(TTL)와 함께 반환한다. 조회 성공이면 {@code Optional.of(set)}, 조회 불가/실패면
     * {@code Optional.empty()}(= 확인 불가 → 호출부는 진행). Ollama 미설치 환경·일시 장애가 요청을 막지 않게 한다.
     */
    private Optional<Set<String>> installedModels() {
        long now = System.nanoTime();
        if (now < installedExpiresAtNanos) {
            return installedVerified ? Optional.of(installedCache) : Optional.empty();
        }
        synchronized (this) {
            if (System.nanoTime() < installedExpiresAtNanos) {
                return installedVerified ? Optional.of(installedCache) : Optional.empty();
            }
            boolean verified = false;
            Set<String> names = Set.of();
            try {
                OllamaApi api = ollamaApiProvider.getIfAvailable();
                if (api != null) {
                    Set<String> collected = new LinkedHashSet<>();
                    for (OllamaApi.Model m : api.listModels().models()) {
                        if (StringUtils.hasText(m.name())) {
                            collected.add(m.name());
                        }
                        if (StringUtils.hasText(m.model())) {
                            collected.add(m.model());
                        }
                    }
                    names = Set.copyOf(collected);
                    verified = true;
                }
            } catch (Exception e) {
                log.warn("ModelCatalog: Ollama 설치 모델 조회 실패 — 설치 확인 생략하고 진행: {}", e.getMessage());
            }
            this.installedCache = names;
            this.installedVerified = verified;
            this.installedExpiresAtNanos = System.nanoTime() + INSTALLED_TTL_NANOS;
            return verified ? Optional.of(names) : Optional.empty();
        }
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
