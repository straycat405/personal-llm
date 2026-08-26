package com.bigteam.btllm.chat.experiment;

import java.util.List;
import java.util.regex.Pattern;

/** 대화 품질 평가셋의 필수 의미와 금지 응답을 정규식으로 판정한다. */
class ConversationAnswerEvaluator {

    record GoldenScenario(
        String id,
        String category,
        List<String> setupPrompts,
        String prompt,
        List<List<String>> requiredPatternGroups,
        List<String> forbiddenPatterns
    ) {
    }

    record Evaluation(
        boolean passed,
        int matchedRequirements,
        int totalRequirements,
        List<List<String>> missingPatternGroups,
        List<String> violatedPatterns
    ) {
    }

    Evaluation evaluate(GoldenScenario scenario, String answer) {
        String safeAnswer = answer == null ? "" : answer;
        List<List<String>> missingGroups = scenario.requiredPatternGroups().stream()
            .filter(group -> group.stream().noneMatch(pattern -> matches(pattern, safeAnswer)))
            .toList();
        List<String> violations = scenario.forbiddenPatterns().stream()
            .filter(pattern -> matches(pattern, safeAnswer))
            .toList();
        int matched = scenario.requiredPatternGroups().size() - missingGroups.size();

        return new Evaluation(
            missingGroups.isEmpty() && violations.isEmpty(),
            matched,
            scenario.requiredPatternGroups().size(),
            missingGroups,
            violations
        );
    }

    private boolean matches(String regex, String text) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
            .matcher(text)
            .find();
    }
}
