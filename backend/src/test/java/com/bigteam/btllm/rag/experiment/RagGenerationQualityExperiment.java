package com.bigteam.btllm.rag.experiment;

import com.bigteam.btllm.chat.service.ThinkingRouter;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 Ollama Tool Calling까지 포함한 RAG 생성 품질 평가.
 * 검색 Recall 실험과 달리 최종 답변의 필수 사실, 금지 오답, 출처 표시를 측정한다.
 */
@SpringBootTest
@Tag("experiment")
class RagGenerationQualityExperiment {

    private static final String TARGET_SOURCE_KEYWORD = "모두의_창업_프로젝트";
    private static final String MODEL = "qwen3:8b";

    @Autowired ChatClientFactory chatClientFactory;
    @Autowired ThinkingRouter thinkingRouter;
    @Autowired LlmTools llmTools;
    @Autowired EtlSourceService etlSourceService;
    @Autowired ObjectMapper objectMapper;

    private final RagAnswerEvaluator evaluator = new RagAnswerEvaluator();

    record QuestionResult(
        RagAnswerEvaluator.GoldenQuestion golden,
        String answer,
        RagAnswerEvaluator.Evaluation evaluation,
        long latencyMs,
        String error
    ) {
    }

    @Test
    void measureGeneratedAnswerQuality() throws Exception {
        assertThat(etlSourceService.listSources())
            .as("평가 대상 PDF가 먼저 인덱싱되어 있어야 합니다")
            .anyMatch(source -> source.source().contains(TARGET_SOURCE_KEYWORD));

        List<RagAnswerEvaluator.GoldenQuestion> goldenSet = loadGoldenSet();
        ChatClient chatClient = chatClientFactory.get("ollama", MODEL);
        List<QuestionResult> results = new ArrayList<>();

        for (int index = 0; index < goldenSet.size(); index++) {
            var golden = goldenSet.get(index);
            System.out.printf("[%d/%d] %s (%s) 시작%n",
                index + 1, goldenSet.size(), golden.id(), golden.category());

            String conversationId = "rag-generation-eval-" + UUID.randomUUID();
            long startedAt = System.nanoTime();
            String answer = "";
            String error = null;
            try {
                // 운영 경로와 동일하게 질의별 thinking 라우팅을 적용한다.
                // 라우팅을 태우지 않으면 실험이 실제 서비스와 다른 조건을 재는 셈이 된다.
                answer = chatClient.prompt()
                    .options(chatClientFactory.ollamaOptions(
                        MODEL, thinkingRouter.shouldThink(golden.question())))
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
            results.add(new QuestionResult(golden, answer, evaluation, latencyMs, error));

            // 장시간 로컬 모델 평가 중 후반 집계 오류나 프로세스 중단이 발생해도
            // 완료된 문항의 답변과 판정은 잃지 않도록 매 문항마다 보고서를 갱신한다.
            writeReport(results, goldenSet.size());

            System.out.printf(Locale.US,
                "[%d/%d] %s 완료 — pass=%s, facts=%d/%d, citation=%s, latency=%.1fs%n",
                index + 1, goldenSet.size(), golden.id(), evaluation.passed(),
                evaluation.matchedFacts(), evaluation.totalFacts(), evaluation.citedSource(), latencyMs / 1000.0);
        }

        assertThat(results).as("평가 실행 중 예외가 없어야 합니다")
            .allMatch(result -> result.error() == null);
    }

    private List<RagAnswerEvaluator.GoldenQuestion> loadGoldenSet() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/rag-eval/generation-golden-set.json")) {
            if (input == null) {
                throw new IllegalStateException("generation-golden-set.json을 찾을 수 없습니다.");
            }
            return List.of(objectMapper.readValue(input, RagAnswerEvaluator.GoldenQuestion[].class));
        }
    }

    private void writeReport(List<QuestionResult> results, int totalQuestionCount) throws Exception {
        int passed = (int) results.stream().filter(result -> result.evaluation().passed()).count();
        int citations = (int) results.stream().filter(result -> result.evaluation().citedSource()).count();
        int matchedFacts = results.stream().mapToInt(result -> result.evaluation().matchedFacts()).sum();
        int totalFacts = results.stream().mapToInt(result -> result.evaluation().totalFacts()).sum();
        double factCoverage = totalFacts == 0 ? 1.0 : (double) matchedFacts / totalFacts;
        double avgLatencyMs = results.stream().mapToLong(QuestionResult::latencyMs).average().orElse(0);
        List<Long> sortedLatencies = results.stream()
            .map(QuestionResult::latencyMs)
            .sorted(Comparator.naturalOrder())
            .toList();
        long p95LatencyMs = sortedLatencies.get(Math.max(0,
            (int) Math.ceil(sortedLatencies.size() * 0.95) - 1));

        StringBuilder report = new StringBuilder();
        report.append("# RAG 생성 품질 실험 결과\n\n");
        report.append("- 실행 시각: ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n");
        report.append("- 모델: ").append(MODEL).append(" / num_ctx=4096\n");
        report.append("- 검색: topK=").append(RagSearchSettings.TOP_K)
            .append(", similarityThreshold=").append(RagSearchSettings.SIMILARITY_THRESHOLD).append("\n");
        report.append("- 평가 문항: ").append(results.size()).append("건\n\n");

        report.append("## 핵심 지표\n\n");
        report.append(String.format(Locale.US,
            "| 문항 통과율 | 필수 사실 포함률 | 출처 표시율 | 평균 응답시간 | p95 응답시간 |\n" +
            "|---:|---:|---:|---:|---:|\n" +
            "| %.1f%% (%d/%d) | %.1f%% (%d/%d) | %.1f%% (%d/%d) | %.1fs | %.1fs |\n\n",
            passed * 100.0 / results.size(), passed, results.size(),
            factCoverage * 100, matchedFacts, totalFacts,
            citations * 100.0 / results.size(), citations, results.size(),
            avgLatencyMs / 1000.0, p95LatencyMs / 1000.0));

        report.append("진행 상태: ").append(results.size()).append("/").append(totalQuestionCount);
        report.append(results.size() == totalQuestionCount ? " (완료)\n\n" : " (실행 중)\n\n");

        report.append("문항 통과 조건: 필수 사실 패턴 100% 포함 + 금지 오답 0건 + `[출처]` 표시.\n\n");
        report.append("## 문항별 결과\n\n");
        report.append("| ID | 범주 | thinking | 통과 | 사실 | 금지 오답 | 출처 | 지연 |\n");
        report.append("|---|---|---|---:|---:|---:|---:|---:|\n");
        for (QuestionResult result : results) {
            var evaluation = result.evaluation();
            // 라우팅이 문항마다 어떻게 판정했는지 남긴다. 지연·품질 차이를 해석할 때
            // 어떤 모드로 답한 결과인지 모르면 비교가 불가능하다.
            String routed = thinkingRouter.resolveReason(result.golden().question());
            report.append(String.format(Locale.US,
                "| %s | %s | " + routed + " | %s | %d/%d | %d | %s | %.1fs |\n",
                result.golden().id(), result.golden().category(), evaluation.passed() ? "✅" : "❌",
                evaluation.matchedFacts(), evaluation.totalFacts(), evaluation.violatedPatterns().size(),
                evaluation.citedSource() ? "✅" : "❌", result.latencyMs() / 1000.0));
        }

        report.append("\n## 상세 응답 및 실패 근거\n");
        for (QuestionResult result : results) {
            report.append("\n### ").append(result.golden().id()).append(" — ")
                .append(result.golden().category()).append("\n\n");
            report.append("**질문:** ").append(result.golden().question()).append("\n\n");
            if (result.error() != null) {
                report.append("**실행 오류:** `").append(result.error()).append("`\n\n");
            }
            report.append("**누락 패턴:** ").append(result.evaluation().missingPatterns()).append("\n\n");
            report.append("**금지 오답 패턴:** ").append(result.evaluation().violatedPatterns()).append("\n\n");
            report.append("```text\n").append(result.answer()).append("\n```\n");
        }

        Path reportPath = Path.of("../docs/rag-generation-quality-experiment.md");
        Files.writeString(reportPath, report.toString());
        System.out.println("평가 보고서 작성 완료: " + reportPath.toAbsolutePath());
    }
}
