package com.bigteam.btllm.chat.experiment;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationAnswerEvaluatorTest {

    private final ConversationAnswerEvaluator evaluator = new ConversationAnswerEvaluator();

    @Test
    void passesWhenEveryRequirementGroupHasAnAlternativeAndNoForbiddenTextExists() {
        var scenario = scenario();

        var evaluation = evaluator.evaluate(scenario, "저는 BTLLM입니다. 표 형식으로 답합니다.");

        assertThat(evaluation.passed()).isTrue();
        assertThat(evaluation.matchedRequirements()).isEqualTo(2);
        assertThat(evaluation.missingPatternGroups()).isEmpty();
        assertThat(evaluation.violatedPatterns()).isEmpty();
    }

    @Test
    void reportsMissingGroupsAndForbiddenPatternsTogether() {
        var scenario = scenario();

        var evaluation = evaluator.evaluate(scenario, "저는 Qwen입니다.");

        assertThat(evaluation.passed()).isFalse();
        assertThat(evaluation.matchedRequirements()).isZero();
        assertThat(evaluation.missingPatternGroups()).hasSize(2);
        assertThat(evaluation.violatedPatterns()).containsExactly("Qwen");
    }

    private ConversationAnswerEvaluator.GoldenScenario scenario() {
        return new ConversationAnswerEvaluator.GoldenScenario(
            "identity", "정체성", List.of(), "너는 누구니?",
            List.of(List.of("BTLLM", "비티엘엘엠"), List.of("표", "table")),
            List.of("Qwen", "알리바바")
        );
    }
}
