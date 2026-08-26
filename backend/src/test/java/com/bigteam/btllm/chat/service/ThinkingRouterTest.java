package com.bigteam.btllm.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("thinking 라우팅 규칙 테스트")
class ThinkingRouterTest {

    private final ThinkingRouter router = new ThinkingRouter();

    @ParameterizedTest
    @DisplayName("여러 정보를 종합해야 하는 질의는 thinking을 켠다")
    @ValueSource(strings = {
        "일반트랙과 로컬트랙은 지원 분야가 어떻게 달라?",     // 비교
        "두 트랙의 차이를 알려줘",                             // 비교
        "이 문서가 어떤 사업인지 핵심만 설명해줘",             // 요약
        "지원 내용을 정리해줘",                                // 요약
        "왜 이 사업에 지원해야 해?"                            // 이유
    })
    void enablesThinkingForComplexQuestions(String question) {
        assertThat(router.shouldThink(question)).isTrue();
    }

    @ParameterizedTest
    @DisplayName("단일 사실을 꺼내오는 짧은 질의는 thinking을 끈다")
    @ValueSource(strings = {
        "신청 마감이 언제야?",
        "신청 사이트 어디야?",
        "상금이 얼마야?"
    })
    void disablesThinkingForSimpleLookups(String question) {
        assertThat(router.shouldThink(question)).isFalse();
    }

    @Test
    @DisplayName("항목별 구분을 요구하면 단순 조회 표현이 섞여도 thinking을 켠다")
    void enablesThinkingForPerCategoryLookups() {
        // "언제"라는 단순 조회 신호가 있지만 트랙마다 답이 달라 종합 판단이 필요하다.
        String question = "사업자등록이 필요한 라운드가 트랙별로 언제인지 알려줘";

        assertThat(router.resolveReason(question)).isEqualTo("비교·구분");
        assertThat(router.shouldThink(question)).isTrue();
    }

    @Test
    @DisplayName("단순 조회 표현이 있어도 문장이 길면 조건이 얽힌 것으로 보고 thinking을 켠다")
    void enablesThinkingForLongLookupQuestions() {
        String question = "예비창업자가 부동산임대업만 영위하고 있는 경우에도 신청이 가능한지, "
            + "그리고 가능하다면 접수 기간이 언제까지인지 알려줘";

        assertThat(question.length()).isGreaterThan(60);
        assertThat(router.shouldThink(question)).isTrue();
    }

    @Test
    @DisplayName("판단이 서지 않으면 정확도를 우선해 thinking을 켠다")
    void defaultsToThinkingWhenUnclear() {
        assertThat(router.shouldThink("그거 알려줘")).isTrue();
        assertThat(router.shouldThink("")).isTrue();
        assertThat(router.shouldThink(null)).isTrue();
    }

    @Test
    @DisplayName("판정 사유를 확인할 수 있다")
    void exposesRoutingReason() {
        assertThat(router.resolveReason("두 트랙의 차이는?")).isEqualTo("비교·구분");
        assertThat(router.resolveReason("핵심만 요약해줘")).isEqualTo("요약·종합");
        assertThat(router.resolveReason("마감 언제야?")).isEqualTo("단순 조회");
    }
}
