package com.bigteam.btllm.rag.experiment;

import java.util.List;
import java.util.regex.Pattern;

final class RagAnswerEvaluator {

    private static final int REGEX_FLAGS = Pattern.CASE_INSENSITIVE | Pattern.DOTALL | Pattern.UNICODE_CASE;

    record GoldenQuestion(
        String id,
        String category,
        String question,
        List<String> requiredPatterns,
        List<String> forbiddenPatterns
    ) {
        GoldenQuestion {
            requiredPatterns = requiredPatterns == null ? List.of() : List.copyOf(requiredPatterns);
            forbiddenPatterns = forbiddenPatterns == null ? List.of() : List.copyOf(forbiddenPatterns);
        }
    }

    record Evaluation(
        int matchedFacts,
        int totalFacts,
        double factCoverage,
        List<String> missingPatterns,
        List<String> violatedPatterns,
        boolean citedSource,
        boolean passed
    ) {
    }

    Evaluation evaluate(GoldenQuestion golden, String answer) {
        String safeAnswer = answer == null ? "" : answer;

        List<String> missing = golden.requiredPatterns().stream()
            .filter(pattern -> !matches(pattern, safeAnswer))
            .toList();
        List<String> violated = golden.forbiddenPatterns().stream()
            .filter(pattern -> matches(pattern, safeAnswer))
            .toList();

        int total = golden.requiredPatterns().size();
        int matched = total - missing.size();
        double coverage = total == 0 ? 1.0 : (double) matched / total;
        boolean cited = Pattern.compile("\\[출처]", REGEX_FLAGS).matcher(safeAnswer).find();
        boolean passed = coverage == 1.0 && violated.isEmpty() && cited;

        return new Evaluation(matched, total, coverage, missing, violated, cited, passed);
    }

    private boolean matches(String regex, String answer) {
        return Pattern.compile(regex, REGEX_FLAGS).matcher(answer).find();
    }
}
