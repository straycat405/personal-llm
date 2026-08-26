package com.bigteam.btllm.chat.experiment;

import com.bigteam.btllm.chat.tools.LlmTools;
import com.bigteam.btllm.config.ChatClientFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 로컬 모델의 정체성, 기억, 주제 전환, 출력 계약 준수율을 반복 측정한다. */
@SpringBootTest
@Tag("conversation-experiment")
class LocalConversationQualityExperiment {

    private static final String MODEL = env("LOCAL_MODEL_EVAL_MODEL", "qwen3:8b");
    private static final double TEMPERATURE = envDouble("LOCAL_MODEL_EVAL_TEMPERATURE", 0.3);
    private static final double TOP_P = envDouble("LOCAL_MODEL_EVAL_TOP_P", 0.8);
    private static final int TOP_K = envInt("LOCAL_MODEL_EVAL_TOP_K", 20);
    private static final boolean THINKING = envBoolean("LOCAL_MODEL_EVAL_THINKING", false);
    private static final int REPETITIONS = envInt("LOCAL_MODEL_EVAL_REPETITIONS", 1);
    private static final int NUM_CTX = envInt("LOCAL_MODEL_EVAL_NUM_CTX", 4096);
    private static final int NUM_PREDICT = envInt("LOCAL_MODEL_EVAL_NUM_PREDICT", 768);

    @Autowired ChatClientFactory chatClientFactory;
    @Autowired LlmTools llmTools;
    @Autowired ObjectMapper objectMapper;

    private final ConversationAnswerEvaluator evaluator = new ConversationAnswerEvaluator();

    record ScenarioResult(
        ConversationAnswerEvaluator.GoldenScenario scenario,
        int repetition,
        String answer,
        ConversationAnswerEvaluator.Evaluation evaluation,
        long latencyMs,
        String error
    ) {
    }

    @Test
    void measureConversationQuality() throws Exception {
        List<ConversationAnswerEvaluator.GoldenScenario> scenarios = loadGoldenSet();
        List<ScenarioResult> results = new ArrayList<>();
        ChatClient chatClient = chatClientFactory.get("ollama", MODEL);
        OllamaChatOptions options = options();

        for (int repetition = 1; repetition <= REPETITIONS; repetition++) {
            for (var scenario : scenarios) {
                String conversationId = "conversation-eval-" + UUID.randomUUID();
                String answer = "";
                String error = null;
                long startedAt = System.nanoTime();
                try {
                    for (String setupPrompt : scenario.setupPrompts()) {
                        call(chatClient, options, conversationId, setupPrompt);
                    }
                    answer = call(chatClient, options, conversationId, scenario.prompt());
                } catch (Exception e) {
                    error = e.getClass().getSimpleName() + ": " + e.getMessage();
                }
                long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
                var evaluation = evaluator.evaluate(scenario, answer);
                results.add(new ScenarioResult(scenario, repetition, answer, evaluation, latencyMs, error));
                writeReport(results, scenarios.size() * REPETITIONS);

                System.out.printf(Locale.US,
                    "[%d/%d] %s #%d — pass=%s, requirements=%d/%d, latency=%.1fs%n",
                    results.size(), scenarios.size() * REPETITIONS, scenario.id(), repetition,
                    evaluation.passed(), evaluation.matchedRequirements(),
                    evaluation.totalRequirements(), latencyMs / 1000.0);
            }
        }

        assertThat(results).as("평가 실행 중 예외가 없어야 합니다")
            .allMatch(result -> result.error() == null);
    }

    private String call(ChatClient chatClient, OllamaChatOptions options,
        String conversationId, String prompt) {
        return chatClient.prompt()
            .options(options)
            .user(prompt)
            .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, conversationId))
            .tools(llmTools)
            .toolContext(Map.of("conversationId", conversationId))
            .call()
            .content();
    }

    private OllamaChatOptions options() {
        var builder = OllamaChatOptions.builder()
            .model(MODEL)
            .temperature(TEMPERATURE)
            .topP(TOP_P)
            .topK(TOP_K)
            .numCtx(NUM_CTX)
            .numPredict(NUM_PREDICT)
            .keepAlive("-1s");
        return (THINKING ? builder.enableThinking() : builder.disableThinking()).build();
    }

    private List<ConversationAnswerEvaluator.GoldenScenario> loadGoldenSet() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/conversation-eval/golden-set.json")) {
            if (input == null) throw new IllegalStateException("conversation golden-set을 찾을 수 없습니다.");
            return List.of(objectMapper.readValue(
                input, ConversationAnswerEvaluator.GoldenScenario[].class));
        }
    }

    private void writeReport(List<ScenarioResult> results, int totalRuns) throws Exception {
        int passed = (int) results.stream().filter(result -> result.evaluation().passed()).count();
        int matched = results.stream().mapToInt(result -> result.evaluation().matchedRequirements()).sum();
        int totalRequirements = results.stream().mapToInt(result -> result.evaluation().totalRequirements()).sum();
        double avgLatencyMs = results.stream().mapToLong(ScenarioResult::latencyMs).average().orElse(0);
        List<Long> latencies = results.stream().map(ScenarioResult::latencyMs)
            .sorted(Comparator.naturalOrder()).toList();
        long p95LatencyMs = latencies.get(Math.max(0,
            (int) Math.ceil(latencies.size() * 0.95) - 1));

        StringBuilder report = new StringBuilder("# 로컬 모델 대화 품질 실험 결과\n\n");
        report.append("- 실행 시각: ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n");
        report.append("- 모델: ").append(MODEL).append("\n");
        report.append(String.format(Locale.US,
            "- 생성 설정: temperature=%.2f, top_p=%.2f, top_k=%d, thinking=%s, num_ctx=%d, num_predict=%d\n",
            TEMPERATURE, TOP_P, TOP_K, THINKING, NUM_CTX, NUM_PREDICT));
        report.append("- 반복 횟수: ").append(REPETITIONS).append("\n\n");
        report.append("## 핵심 지표\n\n");
        report.append("| 시나리오 통과율 | 요구사항 충족률 | 평균 지연 | p95 지연 |\n");
        report.append("|---:|---:|---:|---:|\n");
        report.append(String.format(Locale.US,
            "| %.1f%% (%d/%d) | %.1f%% (%d/%d) | %.1fs | %.1fs |\n\n",
            passed * 100.0 / results.size(), passed, results.size(),
            matched * 100.0 / totalRequirements, matched, totalRequirements,
            avgLatencyMs / 1000.0, p95LatencyMs / 1000.0));
        report.append("진행 상태: ").append(results.size()).append("/").append(totalRuns)
            .append(results.size() == totalRuns ? " (완료)\n\n" : " (실행 중)\n\n");
        report.append("자동 판정은 반복 비교를 위한 엄격한 정규식 기준이다. 아이디어의 실현 가능성처럼 ")
            .append("의미 판단이 필요한 항목은 상세 응답을 별도로 사람 검토한다.\n\n");
        report.append("## 실행별 결과\n\n");
        report.append("| ID | 반복 | 통과 | 요구사항 | 금지 응답 | 지연 |\n");
        report.append("|---|---:|---:|---:|---:|---:|\n");
        for (var result : results) {
            report.append(String.format(Locale.US, "| %s | %d | %s | %d/%d | %d | %.1fs |\n",
                result.scenario().id(), result.repetition(), result.evaluation().passed() ? "✅" : "❌",
                result.evaluation().matchedRequirements(), result.evaluation().totalRequirements(),
                result.evaluation().violatedPatterns().size(), result.latencyMs() / 1000.0));
        }
        report.append("\n## 상세 응답\n");
        for (var result : results) {
            report.append("\n### ").append(result.scenario().id()).append(" #")
                .append(result.repetition()).append("\n\n");
            report.append("**질문:** ").append(result.scenario().prompt()).append("\n\n");
            if (result.error() != null) report.append("**오류:** `").append(result.error()).append("`\n\n");
            report.append("**누락:** ").append(result.evaluation().missingPatternGroups()).append("\n\n");
            report.append("**금지 응답:** ").append(result.evaluation().violatedPatterns()).append("\n\n");
            // 모델이 Markdown hard break용 후행 공백이나 코드 펜스를 반환해도
            // 생성 보고서 자체의 lint와 구조가 깨지지 않게 정규화한다.
            report.append("````text\n").append(stripTrailingWhitespace(result.answer())).append("\n````\n");
        }

        Files.writeString(Path.of("../docs/local-conversation-quality-experiment.md"), report.toString());
    }

    private static String env(String name, String defaultValue) {
        return System.getenv().getOrDefault(name, defaultValue);
    }

    private static int envInt(String name, int defaultValue) {
        return Integer.parseInt(env(name, String.valueOf(defaultValue)));
    }

    private static double envDouble(String name, double defaultValue) {
        return Double.parseDouble(env(name, String.valueOf(defaultValue)));
    }

    private static boolean envBoolean(String name, boolean defaultValue) {
        return Boolean.parseBoolean(env(name, String.valueOf(defaultValue)));
    }

    private static String stripTrailingWhitespace(String text) {
        return text == null ? "" : text.replaceAll("(?m)[ \\t]+$", "");
    }
}
