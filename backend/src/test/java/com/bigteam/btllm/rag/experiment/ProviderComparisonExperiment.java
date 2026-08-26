package com.bigteam.btllm.rag.experiment;

import com.bigteam.btllm.chat.tools.LlmTools;
import com.bigteam.btllm.config.ChatClientFactory;
import com.bigteam.btllm.rag.config.RagSearchSettings;
import com.bigteam.btllm.rag.service.EtlSourceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 동일한 RAG 생성 골든셋을 여러 provider(로컬 Ollama / 상용 API)에 실행해 비교한다.
 *
 * [실험 목적 — 진단적 분리]
 * 로컬 qwen3:8b는 `overview-1`(사업 개요 요약)에서 반복적으로 실패했다. 이 실패가
 *   (A) 검색 파이프라인이 애초에 정답 근거를 못 주는 문제인지,
 *   (B) 8B 소형 모델이 근거를 받고도 못 쓰는 체급 문제인지
 * 로컬 모델만 측정해서는 구분할 수 없다. 동일한 검색 파이프라인(같은 Tool, 같은 재정렬,
 * 같은 topK)에 모델만 바꿔 상용 모델을 붙이면, 상용 모델도 실패할 경우 (A), 상용 모델만
 * 성공할 경우 (B)로 원인을 분리할 수 있다. 즉 상용 API는 대체재가 아니라 **기준선(ceiling)**
 * 으로 쓴다.
 *
 * [공정 비교를 위한 통제]
 * - 같은 골든셋 파일(`/rag-eval/generation-golden-set.json`)과 같은 판정기를 쓴다.
 * - 같은 `LlmTools`(searchKnowledgeBase + HybridReranker)를 주입한다.
 * - 같은 시스템 프롬프트를 쓴다(ChatClientFactory가 provider 무관하게 동일 적용).
 * - provider별 생성 옵션은 ChatClientFactory가 bake-in한 값(temperature 0.3 공통)을 따른다.
 *
 * [주의] 상용 provider는 실제 과금이 발생한다. 대상은 PROVIDER_COMPARISON_TARGETS로 통제하며
 *   기본값에도 상용 모델이 포함되므로, 키가 설정된 상태에서 실행하면 비용이 발생한다.
 *   키가 없는 provider는 자동으로 건너뛰고 보고서에 사유를 남긴다.
 */
@SpringBootTest
@Tag("provider-comparison")
class ProviderComparisonExperiment {

    private static final String TARGET_SOURCE_KEYWORD = "모두의_창업_프로젝트";

    /** `provider=model` 목록. 모델명에 콜론(qwen3:8b)이 있어 구분자로 `=`를 쓴다. */
    private static final String TARGETS = System.getenv()
        .getOrDefault("PROVIDER_COMPARISON_TARGETS", "ollama=qwen3:8b,openai=gpt-4o-mini");

    @Autowired ChatClientFactory chatClientFactory;
    @Autowired LlmTools llmTools;
    @Autowired EtlSourceService etlSourceService;
    @Autowired ObjectMapper objectMapper;

    private final RagAnswerEvaluator evaluator = new RagAnswerEvaluator();

    record Target(String provider, String model) {
        String label() {
            return provider + " / " + model;
        }
    }

    record RunResult(
        Target target,
        RagAnswerEvaluator.GoldenQuestion golden,
        String answer,
        RagAnswerEvaluator.Evaluation evaluation,
        long latencyMs,
        String error
    ) {
    }

    @Test
    void compareProvidersOnSameGoldenSet() throws Exception {
        assertThat(etlSourceService.listSources())
            .as("평가 대상 PDF가 먼저 인덱싱되어 있어야 합니다")
            .anyMatch(source -> source.source().contains(TARGET_SOURCE_KEYWORD));

        List<RagAnswerEvaluator.GoldenQuestion> goldenSet = loadGoldenSet();
        List<Target> targets = parseTargets();
        List<RunResult> results = new ArrayList<>();
        Map<String, String> skipped = new LinkedHashMap<>();

        for (Target target : targets) {
            if (!chatClientFactory.isAvailable(target.provider())) {
                skipped.put(target.label(), "API 키 미설정으로 provider 비활성화");
                System.out.printf("[skip] %s — API 키 미설정%n", target.label());
                continue;
            }

            ChatClient chatClient = chatClientFactory.get(target.provider(), target.model());
            for (int index = 0; index < goldenSet.size(); index++) {
                var golden = goldenSet.get(index);
                String conversationId = "provider-comparison-" + UUID.randomUUID();
                long startedAt = System.nanoTime();
                String answer = "";
                String error = null;
                try {
                    answer = chatClient.prompt()
                        .user(golden.question())
                        .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, conversationId))
                        .tools(llmTools)
                        .toolContext(Map.of("conversationId", conversationId))
                        .call()
                        .content();
                } catch (Exception e) {
                    error = e.getClass().getSimpleName() + ": " + e.getMessage();
                }
                long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
                var evaluation = evaluator.evaluate(golden, answer);
                results.add(new RunResult(target, golden, answer, evaluation, latencyMs, error));

                // 장시간 실행 중 중단되어도 완료분을 잃지 않도록 매 문항마다 보고서를 갱신한다.
                writeReport(results, skipped, targets, goldenSet.size());

                System.out.printf(Locale.US,
                    "[%s] [%d/%d] %s — pass=%s, facts=%d/%d, latency=%.1fs%s%n",
                    target.label(), index + 1, goldenSet.size(), golden.id(),
                    evaluation.passed(), evaluation.matchedFacts(), evaluation.totalFacts(),
                    latencyMs / 1000.0, error == null ? "" : " ERROR=" + error);
            }
        }

        assertThat(results).as("최소 한 개 provider는 실행되어야 합니다").isNotEmpty();
    }

    private List<Target> parseTargets() {
        List<Target> targets = new ArrayList<>();
        for (String entry : TARGETS.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            if (separator < 0) {
                throw new IllegalArgumentException(
                    "PROVIDER_COMPARISON_TARGETS 형식은 provider=model 입니다: " + trimmed);
            }
            targets.add(new Target(
                trimmed.substring(0, separator).trim(),
                trimmed.substring(separator + 1).trim()));
        }
        return targets;
    }

    private List<RagAnswerEvaluator.GoldenQuestion> loadGoldenSet() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/rag-eval/generation-golden-set.json")) {
            if (input == null) {
                throw new IllegalStateException("generation-golden-set.json을 찾을 수 없습니다.");
            }
            return List.of(objectMapper.readValue(input, RagAnswerEvaluator.GoldenQuestion[].class));
        }
    }

    private void writeReport(List<RunResult> results, Map<String, String> skipped,
        List<Target> targets, int questionCount) throws Exception {

        StringBuilder report = new StringBuilder("# Provider 비교 실험 결과 (동일 RAG 골든셋)\n\n");
        report.append("- 실행 시각: ")
            .append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n");
        report.append("- 검색 설정(전 provider 공통): topK=").append(RagSearchSettings.TOP_K)
            .append(", 재정렬 후보=").append(RagSearchSettings.RERANK_CANDIDATE_K)
            .append(", similarityThreshold=").append(RagSearchSettings.SIMILARITY_THRESHOLD).append("\n");
        report.append("- 평가 문항: ").append(questionCount).append("건 (동일 골든셋)\n");
        report.append("- 대상: ").append(TARGETS).append("\n\n");

        report.append("동일한 검색 파이프라인·시스템 프롬프트·골든셋에 **모델만 교체**해 실행한다. ")
            .append("상용 모델은 대체재가 아니라 실패 원인을 검색(파이프라인) 문제와 ")
            .append("모델 체급 문제로 분리하기 위한 기준선으로 쓴다.\n\n");

        if (!skipped.isEmpty()) {
            report.append("## 건너뛴 대상\n\n");
            skipped.forEach((label, reason) ->
                report.append("- `").append(label).append("` — ").append(reason).append("\n"));
            report.append("\n");
        }

        report.append("## Provider별 요약\n\n");
        report.append("| Provider / 모델 | 문항 통과율 | 필수 사실 포함률 | 출처 표시율 | 평균 지연 | p95 지연 |\n");
        report.append("|---|---:|---:|---:|---:|---:|\n");
        for (Target target : targets) {
            List<RunResult> runs = results.stream()
                .filter(result -> result.target().equals(target)).toList();
            if (runs.isEmpty()) {
                continue;
            }
            int passed = (int) runs.stream().filter(run -> run.evaluation().passed()).count();
            int cited = (int) runs.stream().filter(run -> run.evaluation().citedSource()).count();
            int matchedFacts = runs.stream().mapToInt(run -> run.evaluation().matchedFacts()).sum();
            int totalFacts = runs.stream().mapToInt(run -> run.evaluation().totalFacts()).sum();
            double avgLatencyMs = runs.stream().mapToLong(RunResult::latencyMs).average().orElse(0);
            List<Long> sorted = runs.stream().map(RunResult::latencyMs)
                .sorted(Comparator.naturalOrder()).toList();
            long p95 = sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * 0.95) - 1));

            report.append(String.format(Locale.US,
                "| %s | %.1f%% (%d/%d) | %.1f%% (%d/%d) | %.1f%% (%d/%d) | %.1fs | %.1fs |\n",
                target.label(),
                passed * 100.0 / runs.size(), passed, runs.size(),
                totalFacts == 0 ? 100.0 : matchedFacts * 100.0 / totalFacts, matchedFacts, totalFacts,
                cited * 100.0 / runs.size(), cited, runs.size(),
                avgLatencyMs / 1000.0, p95 / 1000.0));
        }

        report.append("\n## 문항별 통과 여부 비교\n\n");
        report.append("| 문항 | 범주 |");
        for (Target target : targets) {
            report.append(" ").append(target.label()).append(" |");
        }
        report.append("\n|---|---|");
        report.append("---:|".repeat(targets.size()));
        report.append("\n");

        List<RagAnswerEvaluator.GoldenQuestion> seen = new ArrayList<>();
        for (RunResult result : results) {
            if (seen.stream().noneMatch(golden -> golden.id().equals(result.golden().id()))) {
                seen.add(result.golden());
            }
        }
        for (var golden : seen) {
            report.append("| ").append(golden.id()).append(" | ").append(golden.category()).append(" |");
            for (Target target : targets) {
                String cell = results.stream()
                    .filter(run -> run.target().equals(target) && run.golden().id().equals(golden.id()))
                    .findFirst()
                    .map(run -> String.format(Locale.US, "%s %d/%d",
                        run.evaluation().passed() ? "✅" : "❌",
                        run.evaluation().matchedFacts(), run.evaluation().totalFacts()))
                    .orElse("—");
                report.append(" ").append(cell).append(" |");
            }
            report.append("\n");
        }

        report.append("\n## 상세 응답\n");
        for (var golden : seen) {
            report.append("\n### ").append(golden.id()).append(" — ").append(golden.category()).append("\n\n");
            report.append("**질문:** ").append(golden.question()).append("\n\n");
            for (Target target : targets) {
                results.stream()
                    .filter(run -> run.target().equals(target) && run.golden().id().equals(golden.id()))
                    .findFirst()
                    .ifPresent(run -> {
                        report.append("#### ").append(target.label()).append("\n\n");
                        if (run.error() != null) {
                            report.append("**오류:** `").append(run.error()).append("`\n\n");
                        }
                        report.append("**누락 패턴:** ").append(run.evaluation().missingPatterns()).append("\n\n");
                        report.append("**금지 오답:** ").append(run.evaluation().violatedPatterns()).append("\n\n");
                        report.append("````text\n")
                            .append(stripTrailingWhitespace(run.answer()))
                            .append("\n````\n\n");
                    });
            }
        }

        Files.writeString(Path.of("../docs/provider-comparison-experiment.md"), report.toString());
    }

    private static String stripTrailingWhitespace(String text) {
        return text == null ? "" : text.replaceAll("(?m)[ \\t]+$", "");
    }
}
