package com.bigteam.btllm.rag.experiment;

import com.bigteam.btllm.rag.config.RagSearchSettings;
import com.bigteam.btllm.rag.service.HybridReranker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 실패가 고착된 문항에서 **검색이 근거를 주고 있는지**만 따로 확인하는 진단 도구.
 *
 * [배경] `track-1`·`registration-1`·`prize-1`은 로컬·상용 두 모델이 12회 시도 전부 실패했다.
 *   모델 교체로 풀리지 않음이 확정됐으므로 남은 원인은 파이프라인이다. 셋 다 **두 트랙의 정보를
 *   모두 모아야** 답할 수 있고, 금지 패턴은 트랙 간 **교차 귀속**(일반/기술트랙 상금을 로컬 값으로
 *   서술하는 등)을 잡는다.
 *
 * [무엇을 가르는가]
 *   (A) 필수 사실이 검색 근거 안에 **없다** → 검색 문제. topK 상향·청킹 재설계가 답이다.
 *   (B) 근거 안에 **있는데** 답이 틀린다 → 생성 문제. 근거 제시 방식·프롬프트가 답이다.
 * 최종 답변만 봐서는 이 둘을 구분할 수 없다.
 */
@SpringBootTest
@Tag("reindex")
class TrackAttributionDiagnostic {

    private static final List<String> TARGET_IDS = List.of("track-1", "registration-1", "prize-1");
    private static final int REGEX_FLAGS =
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL | Pattern.UNICODE_CASE;

    /** 검색 폭을 넓히면 근거가 들어오는지 보기 위해 여러 topK를 함께 잰다. */
    private static final List<Integer> TOP_K_CANDIDATES = List.of(3, 4, 5, 8);

    @Autowired VectorStore vectorStore;
    @Autowired HybridReranker hybridReranker;
    @Autowired ObjectMapper objectMapper;

    @Test
    void reportEvidenceCoverageForFailingQuestions() throws Exception {
        List<RagAnswerEvaluator.GoldenQuestion> goldenSet = loadGoldenSet();

        for (var golden : goldenSet) {
            if (!TARGET_IDS.contains(golden.id())) {
                continue;
            }
            System.out.printf("%n════ %s — %s%n", golden.id(), golden.question());

            List<Document> candidates = vectorStore.similaritySearch(
                SearchRequest.builder()
                    .query(golden.question())
                    .topK(Math.max(RagSearchSettings.RERANK_CANDIDATE_K,
                        TOP_K_CANDIDATES.get(TOP_K_CANDIDATES.size() - 1)))
                    .similarityThreshold(RagSearchSettings.SIMILARITY_THRESHOLD)
                    .build());

            System.out.printf("  후보 풀: %d건%n", candidates.size());

            for (int topK : TOP_K_CANDIDATES) {
                List<Document> selected = hybridReranker.rerank(golden.question(), candidates, topK);
                String evidence = selected.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n"));

                long matched = golden.requiredPatterns().stream()
                    .filter(pattern -> find(pattern, evidence))
                    .count();
                int estimatedTokens = (int) (evidence.length() / 3.6);

                System.out.printf(Locale.US,
                    "  topK=%d → 필수 사실 %d/%d 근거에 존재, 근거 %,d자 (약 %,d토큰)%s%n",
                    topK, matched, golden.requiredPatterns().size(),
                    evidence.length(), estimatedTokens,
                    estimatedTokens > 4096 ? "  ⚠ num_ctx 4096 초과" : "");

                if (topK == TOP_K_CANDIDATES.get(0)) {
                    for (String pattern : golden.requiredPatterns()) {
                        System.out.printf("      [%s] %s%n",
                            find(pattern, evidence) ? "있음" : "없음", pattern);
                    }
                }
            }
        }
    }

    private boolean find(String regex, String text) {
        return Pattern.compile(regex, REGEX_FLAGS).matcher(text).find();
    }

    private List<RagAnswerEvaluator.GoldenQuestion> loadGoldenSet() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/rag-eval/generation-golden-set.json")) {
            if (input == null) {
                throw new IllegalStateException("generation-golden-set.json을 찾을 수 없습니다.");
            }
            return List.of(objectMapper.readValue(input, RagAnswerEvaluator.GoldenQuestion[].class));
        }
    }
}
