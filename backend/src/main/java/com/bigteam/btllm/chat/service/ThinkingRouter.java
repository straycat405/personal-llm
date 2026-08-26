package com.bigteam.btllm.chat.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * [역할] 질의 성격에 따라 qwen3의 thinking 모드를 켤지 결정한다.
 *
 * [배경] thinking은 지연과 품질을 정면으로 맞바꾼다(2026-08-27 골든셋 실측).
 *     thinking ON   평균 88.9s / p95 188.6s, 필수 사실 포함률 77.8%
 *     thinking OFF  평균 33.0s / p95  74.5s, 필수 사실 포함률 57.1%
 *   전부 켜면 느리고 전부 끄면 부정확하다. 복합 추론이 필요한 질의에만 켜는 것이 목표다.
 *
 * [설계 결정사항]
 * - **LLM 분류기를 쓰지 않는다.** 라우팅 판단에 LLM을 한 번 더 호출하면 지연을 줄이려는
 *   목적과 모순된다. 정규식 판정은 사실상 0ms다.
 * - **골든셋 문항에 맞춘 규칙을 만들지 않는다.** 그렇게 하면 평가셋에 과적합되어 측정이
 *   무의미해진다. 아래 신호는 도메인과 무관하게 "여러 정보를 종합해야 하는 질문"을 가리키는
 *   일반적인 한국어 표현으로 한정했다.
 * - 판단이 서지 않으면 **thinking을 켠다.** 이 서비스의 존재 이유는 문서 답변의 정확도이므로,
 *   틀리게 빨리 답하는 것보다 느리더라도 맞게 답하는 쪽이 기본값이어야 한다.
 */
@Slf4j
@Component
public class ThinkingRouter {

    private static final int REGEX_FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    /**
     * 여러 대상을 견주거나 구분해야 하는 질의.
     * 답을 하나 찾는 것이 아니라 찾은 것들 사이의 관계를 판단해야 한다.
     */
    private static final Pattern COMPARISON = Pattern.compile(
        // `트랙별로`, `지역별`처럼 "~별"로 끝나는 표현은 항목마다 답을 나눠야 한다는 신호다.
        // 단순 조회 표현("언제")이 함께 있어도 이쪽이 우선한다 — 답이 하나가 아니기 때문이다.
        "비교|차이|다른\\s*점|어떻게\\s*다|각각|각\\s|구분|나뉘|둘\\s*다|양쪽|[가-힣]+별(로|\\s|은|는|$)",
        REGEX_FLAGS);

    /**
     * 전체를 훑어 요약·종합해야 하는 질의.
     * 근거 여러 조각을 모아 하나의 서술로 재구성해야 한다.
     */
    private static final Pattern SYNTHESIS = Pattern.compile(
        "요약|정리|개요|전반|전체적|핵심|어떤\\s*(사업|문서|내용|서비스)|무슨\\s*(사업|문서|내용)|설명해",
        REGEX_FLAGS);

    /**
     * 이유·근거·판단을 요구하는 질의.
     */
    private static final Pattern REASONING = Pattern.compile(
        "왜|이유|근거|어째서|타당|적절한지|가능한지|해야\\s*하나|괜찮",
        REGEX_FLAGS);

    /**
     * 단일 사실을 그대로 꺼내오면 되는 질의.
     * 이런 질문은 thinking 없이도 정확도가 유지되는 것으로 관측됐다.
     */
    private static final Pattern SIMPLE_LOOKUP = Pattern.compile(
        "언제|몇\\s*시|며칠|어디|얼마|몇\\s*명|몇\\s*개|누구|연락처|주소|사이트|링크|기간",
        REGEX_FLAGS);

    /** 이 길이를 넘으면 조건이 여러 개 얽힌 질문으로 보고 thinking을 켠다. */
    private static final int LONG_QUESTION_CHARS = 60;

    public boolean shouldThink(String question) {
        if (question == null || question.isBlank()) {
            return true;   // 판단 불가 시 정확도 우선
        }

        String reason = resolveReason(question);
        boolean think = !"단순 조회".equals(reason);
        log.debug("thinking 라우팅 — think={}, 사유={}, 질의={}", think, reason,
            question.length() > 40 ? question.substring(0, 40) + "..." : question);
        return think;
    }

    /**
     * 판정 사유 — 로그·실험 보고서에서 "왜 이 모드로 답했는지"를 확인할 수 있게 공개한다.
     * 라우팅 판정이 보이지 않으면 지연·품질 차이를 해석할 수 없다.
     */
    public String resolveReason(String question) {
        if (COMPARISON.matcher(question).find()) {
            return "비교·구분";
        }
        if (SYNTHESIS.matcher(question).find()) {
            return "요약·종합";
        }
        if (REASONING.matcher(question).find()) {
            return "이유·판단";
        }
        // 단순 조회 신호가 뚜렷하고 문장이 짧으면 thinking 없이 처리한다.
        if (SIMPLE_LOOKUP.matcher(question).find() && question.length() <= LONG_QUESTION_CHARS) {
            return "단순 조회";
        }
        if (question.length() > LONG_QUESTION_CHARS) {
            return "긴 질의(조건 복합 가능성)";
        }
        return "기본값(정확도 우선)";
    }
}
