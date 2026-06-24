package com.kyuloud.ai.common.advisor;

import com.kyuloud.ai.common.exception.GuardrailException;
import com.kyuloud.ai.common.guardrail.PiiMasker;
import com.kyuloud.ai.config.GuardrailProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Phase 4c — 가드레일 Advisor.
 *
 * <p>모든 {@code ChatClient} 호출을 <em>가장 바깥</em>에서 감싸 입력을 검사한다(로깅·메트릭·메모리보다 먼저).
 *
 * <ul>
 *   <li><b>입력 차단</b>: 사용자 메시지에 금칙어가 있으면 {@link GuardrailException} 으로 차단(모델 호출 없음 → 표준 400 응답).</li>
 *   <li><b>입력 마스킹</b>: 사용자 메시지의 PII 를 마스킹한 뒤 진행한다. 가장 바깥에서 처리하므로 마스킹된 값만
 *       로그·메모리·모델로 흘러가 PII 가 저장/전송되지 않는다.</li>
 *   <li><b>출력 후처리(비파괴)</b>: 응답에 PII/금칙어가 있으면 경고 로깅만 한다. 응답 <em>재작성</em>은 도구호출
 *       중간 응답 손상·스트리밍 경계 분할 문제로 이 단계에서는 다루지 않는다(탐지·관측에 집중).</li>
 * </ul>
 */
@Slf4j
@Component
public class GuardrailAdvisor implements BaseAdvisor {

    private final GuardrailProperties properties;
    private final PiiMasker piiMasker;

    public GuardrailAdvisor(GuardrailProperties properties, PiiMasker piiMasker) {
        this.properties = properties;
        this.piiMasker = piiMasker;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain advisorChain) {
        if (!properties.isEnabled()) {
            return request;
        }
        String userText = userText(request);

        String bannedWord = findBannedWord(userText);
        if (bannedWord != null) {
            log.warn("가드레일 차단: 요청에 금칙어 포함");
            throw new GuardrailException("요청에 허용되지 않는 표현이 포함되어 처리할 수 없습니다.");
        }

        if (properties.isMaskPii()) {
            String masked = piiMasker.mask(userText);
            if (!masked.equals(userText)) {
                log.info("가드레일: 요청 PII 마스킹 적용 {}", piiMasker.findTypes(userText));
                Prompt maskedPrompt = request.prompt()
                        .augmentUserMessage(userMessage -> userMessage.mutate().text(masked).build());
                return request.mutate().prompt(maskedPrompt).build();
            }
        }
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain advisorChain) {
        if (!properties.isEnabled()) {
            return response;
        }
        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse != null && chatResponse.getResult() != null) {
            String output = chatResponse.getResult().getOutput().getText();
            List<String> pii = piiMasker.findTypes(output);
            if (!pii.isEmpty()) {
                log.warn("가드레일: 응답에 PII 의심 패턴 감지 {}", pii);
            }
            if (findBannedWord(output) != null) {
                log.warn("가드레일: 응답에 금칙어 감지");
            }
        }
        return response;
    }

    /**
     * 가장 바깥에서 입력을 검사/마스킹하도록 로깅·메트릭·메모리 advisor 보다 더 높은 우선순위(낮은 order)를 부여한다.
     */
    @Override
    public int getOrder() {
        return Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER - 110;
    }

    private String findBannedWord(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String lower = text.toLowerCase();
        for (String word : properties.getBannedWords()) {
            if (StringUtils.hasText(word) && lower.contains(word.toLowerCase())) {
                return word;
            }
        }
        return null;
    }

    private String userText(ChatClientRequest request) {
        try {
            return request.prompt().getUserMessage().getText();
        } catch (Exception e) {
            return request.prompt().getContents();
        }
    }
}
