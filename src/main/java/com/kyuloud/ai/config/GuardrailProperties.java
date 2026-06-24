package com.kyuloud.ai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 4c — 가드레일 설정.
 * {@code application*.yaml} 의 {@code kyuloud.guardrail.*} 로 외부화한다.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "kyuloud.guardrail")
public class GuardrailProperties {

    /** 가드레일 전체 활성화 여부. */
    private boolean enabled = true;

    /** 요청 본문에서 PII(이메일·전화·주민번호·카드번호)를 마스킹할지 여부. */
    private boolean maskPii = true;

    /** 차단할 금칙어 목록(부분 일치, 대소문자 무시). 포함 시 요청을 차단한다. */
    private List<String> bannedWords = new ArrayList<>();
}
