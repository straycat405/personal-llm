package com.bigteam.btllm.rag.experiment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RAG 생성 답변 평가기 테스트")
class RagAnswerEvaluatorTest {

    private final RagAnswerEvaluator evaluator = new RagAnswerEvaluator();

    @Test
    @DisplayName("필수 사실과 출처가 있고 금지 오답이 없으면 통과한다")
    void passesGroundedAnswer() {
        var golden = new RagAnswerEvaluator.GoldenQuestion(
            "q1", "overview", "질문",
            List.of("일반/기술.{0,20}8,?000", "로컬.{0,20}2,?000"),
            List.of("일반/기술.{0,20}2R 사업자등록")
        );

        var result = evaluator.evaluate(golden,
            "일반/기술트랙 8,000명, 로컬트랙 2,000명을 선정합니다.\n[출처] 공고.pdf");

        assertThat(result.passed()).isTrue();
        assertThat(result.factCoverage()).isEqualTo(1.0);
        assertThat(result.citedSource()).isTrue();
    }

    @Test
    @DisplayName("필수 사실 누락과 금지 오답을 각각 기록한다")
    void reportsMissingAndForbiddenFacts() {
        var golden = new RagAnswerEvaluator.GoldenQuestion(
            "q2", "round", "질문",
            List.of("일반/기술", "3R.{0,20}사업자등록"),
            List.of("일반/기술.{0,30}2R.{0,20}사업자등록")
        );

        var result = evaluator.evaluate(golden,
            "일반/기술트랙은 2R에서 사업자등록이 필요합니다.");

        assertThat(result.passed()).isFalse();
        assertThat(result.matchedFacts()).isEqualTo(1);
        assertThat(result.missingPatterns()).hasSize(1);
        assertThat(result.violatedPatterns()).hasSize(1);
        assertThat(result.citedSource()).isFalse();
    }

    @Test
    @DisplayName("null 패턴 목록과 null 답변을 안전하게 처리한다")
    void handlesNulls() {
        var golden = new RagAnswerEvaluator.GoldenQuestion(
            "q3", "empty", "질문", null, null);

        var result = evaluator.evaluate(golden, null);

        assertThat(result.factCoverage()).isEqualTo(1.0);
        assertThat(result.passed()).isFalse();
        assertThat(result.citedSource()).isFalse();
    }
}
