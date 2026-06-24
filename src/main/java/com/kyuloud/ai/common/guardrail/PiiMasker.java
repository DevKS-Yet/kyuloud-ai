package com.kyuloud.ai.common.guardrail;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 4c — PII(개인식별정보) 마스킹/탐지기.
 *
 * <p>이메일·전화번호·주민등록번호·신용카드번호를 정규식으로 찾아 마스킹하거나, 어떤 유형이 포함됐는지 탐지한다.
 * 가드레일 advisor 와 분리된 순수 로직 컴포넌트라 단위 테스트(4e)가 용이하다.
 *
 * <p>규칙 적용 순서가 중요하다: 자릿수가 겹치는 카드/주민번호를 전화번호보다 먼저 처리한다.
 */
@Component
public class PiiMasker {

    private record Rule(String type, Pattern pattern, String mask) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule("EMAIL",
                    Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+"), "[이메일]"),
            new Rule("RRN",
                    Pattern.compile("\\b\\d{6}-\\d{7}\\b"), "[주민번호]"),
            new Rule("CARD",
                    Pattern.compile("\\b(?:\\d{4}[-\\s]?){3}\\d{4}\\b"), "[카드번호]"),
            new Rule("PHONE",
                    Pattern.compile("\\b01[016789][-\\s]?\\d{3,4}[-\\s]?\\d{4}\\b"), "[전화번호]")
    );

    /** 텍스트의 PII 를 마스킹해 반환한다. PII 가 없으면 원문을 그대로 반환한다. */
    public String mask(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String result = text;
        for (Rule rule : RULES) {
            result = rule.pattern().matcher(result)
                    .replaceAll(Matcher.quoteReplacement(rule.mask()));
        }
        return result;
    }

    /** 텍스트에 포함된 PII 유형 목록을 반환한다(없으면 빈 목록). 탐지 전용(비파괴). */
    public List<String> findTypes(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(text).find()) {
                found.add(rule.type());
            }
        }
        return found;
    }
}
