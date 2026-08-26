package com.bigteam.btllm.rag.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.bigteam.btllm.rag.config.RagSearchSettings.SIMILARITY_THRESHOLD;
import static com.bigteam.btllm.rag.config.RagSearchSettings.TOP_K;

/**
 * [역할] RAG 검색 정확도 실험 — 청크 크기(800/1500/3000)별 Recall@k 측정
 *
 * [실행 조건] 로컬 Ollama(bge-m3) + pgVector Docker 컨테이너 필요
 *   ./gradlew ragAccuracyExperiment
 *
 * [설계 결정사항]
 * - 실 운영 vector_store 테이블을 그대로 사용 — 별도 테이블 분리 대신
 *   metadata "experiment"="rag-eval" 태그로 격리, 실험 종료 시 전량 삭제
 *   (짧게 끝나는 로컬 1회성 실험이라 운영 중 채팅에 잠깐 섞일 위험은 감수)
 * - 청크 크기별로 이전 실험 데이터를 삭제 후 재적재 — 적재 시간(임베딩 호출)도
 *   같이 측정되도록 매번 처음부터 다시 임베딩
 * - Recall@k: 골든셋 쿼리 1건당 정답 문서가 정확히 1개(sourceUrl)이므로
 *   Hit Rate@k와 수학적으로 동일 — "정답 청크가 top-k 안에 있었는가"의 비율
 * - similarityThreshold=0.0으로 순수 랭킹 성능을 먼저 측정하고,
 *   별도로 현재 운영 설정 기준 Recall도 함께 측정
 *   → "지금 운영 중인 설정으로 실제 몇 %가 검색되는가"를 바로 보여주기 위함
 */
@Slf4j
@SpringBootTest
@Tag("experiment")
class RagAccuracyExperiment {

    @Autowired
    private VectorStore vectorStore;

    private static final List<String> CORPUS_URLS = List.of(
        "https://docs.spring.io/spring-ai/reference/api/advisors.html",
        "https://docs.spring.io/spring-ai/reference/api/chatclient.html",
        "https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html",
        "https://docs.spring.io/spring-ai/reference/api/vectordbs.html",
        "https://docs.spring.io/spring-ai/reference/api/etl-pipeline.html",
        "https://docs.spring.io/spring-ai/reference/api/chat-memory.html",
        "https://docs.spring.io/spring-ai/reference/api/tools.html"
    );

    private static final int[] CHUNK_SIZES = {800, 1500, 3000};
    private static final int MAX_K = 10;
    private static final String EXPERIMENT_TAG = "rag-eval";

    record GoldenQuery(String id, String topic, String difficulty, String sourceUrl, String query) {}

    record QueryResult(String id, String difficulty, boolean hit1, boolean hit3, boolean hit5, boolean hit10,
                        boolean hitProdSettings, long latencyMs) {}

    record ChunkSizeResult(int chunkSize, int chunkCount, long ingestMs,
                            double recallAt1, double recallAt3, double recallAt5, double recallAt10,
                            double recallProdSettings, double avgQueryLatencyMs,
                            Map<String, Double> recallAt5ByDifficulty) {}

    @Test
    void measureRagAccuracyAcrossChunkSizes() throws Exception {
        List<GoldenQuery> goldenSet = loadGoldenSet();
        log.info("골든셋 {}건 로드 완료", goldenSet.size());

        List<Document> rawDocs = crawlCorpus();
        log.info("코퍼스 {}개 URL 크롤링 완료", rawDocs.size());

        List<ChunkSizeResult> results = new ArrayList<>();
        for (int chunkSize : CHUNK_SIZES) {
            log.info("=== 청크 크기 {} 실험 시작 ===", chunkSize);
            results.add(runExperimentForChunkSize(chunkSize, rawDocs, goldenSet));
        }

        // 실험 데이터 정리 — 운영 vector_store에 실험 청크가 남지 않도록
        vectorStore.delete("experiment == '" + EXPERIMENT_TAG + "'");
        log.info("실험 데이터 정리 완료");

        writeReport(results, goldenSet.size());
    }

    private List<Document> crawlCorpus() throws IOException {
        List<Document> docs = new ArrayList<>();
        for (String url : CORPUS_URLS) {
            org.jsoup.nodes.Document html = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .timeout(15_000)
                .get();
            String text = html.body().text();
            docs.add(new Document(text, Map.of("source", url)));
        }
        return docs;
    }

    private ChunkSizeResult runExperimentForChunkSize(int chunkSize, List<Document> rawDocs, List<GoldenQuery> goldenSet) {
        // 이전 청크 크기 실험 데이터 삭제 — 매번 깨끗한 상태에서 적재 시간까지 측정
        vectorStore.delete("experiment == '" + EXPERIMENT_TAG + "'");

        TokenTextSplitter splitter = new TokenTextSplitter(chunkSize, 200, 5, 10000, true,
            List.of('.', '?', '!', '\n'));
        List<Document> chunks = splitter.apply(rawDocs);
        chunks.forEach(d -> {
            d.getMetadata().put("experiment", EXPERIMENT_TAG);
            d.getMetadata().put("chunk_size", String.valueOf(chunkSize));
        });

        long ingestStart = System.currentTimeMillis();
        vectorStore.accept(chunks);
        long ingestMs = System.currentTimeMillis() - ingestStart;
        log.info("청크 {}개 적재 완료 — {}ms", chunks.size(), ingestMs);

        List<QueryResult> queryResults = new ArrayList<>();
        for (GoldenQuery gq : goldenSet) {
            long qStart = System.nanoTime();
            List<Document> hits = vectorStore.similaritySearch(
                SearchRequest.builder()
                    .query(gq.query())
                    .topK(MAX_K)
                    .similarityThreshold(0.0)
                    .filterExpression("experiment == '" + EXPERIMENT_TAG + "'")
                    .build()
            );
            long latencyMs = (System.nanoTime() - qStart) / 1_000_000;

            List<String> rankedSources = hits.stream()
                .map(d -> String.valueOf(d.getMetadata().get("source")))
                .toList();

            boolean hit1 = topNContains(rankedSources, 1, gq.sourceUrl());
            boolean hit3 = topNContains(rankedSources, 3, gq.sourceUrl());
            boolean hit5 = topNContains(rankedSources, 5, gq.sourceUrl());
            boolean hit10 = rankedSources.contains(gq.sourceUrl());

            // 운영 설정(topK=5, similarityThreshold=0.5) 기준 별도 조회
            List<Document> prodHits = vectorStore.similaritySearch(
                SearchRequest.builder()
                    .query(gq.query())
                    .topK(TOP_K)
                    .similarityThreshold(SIMILARITY_THRESHOLD)
                    .filterExpression("experiment == '" + EXPERIMENT_TAG + "'")
                    .build()
            );
            boolean hitProd = prodHits.stream()
                .anyMatch(d -> gq.sourceUrl().equals(String.valueOf(d.getMetadata().get("source"))));

            queryResults.add(new QueryResult(gq.id(), gq.difficulty(), hit1, hit3, hit5, hit10, hitProd, latencyMs));
        }

        return aggregate(chunkSize, chunks.size(), ingestMs, queryResults);
    }

    private boolean topNContains(List<String> ranked, int n, String target) {
        return ranked.subList(0, Math.min(n, ranked.size())).contains(target);
    }

    private ChunkSizeResult aggregate(int chunkSize, int chunkCount, long ingestMs, List<QueryResult> queryResults) {
        double n = queryResults.size();
        double recall1 = queryResults.stream().filter(QueryResult::hit1).count() / n;
        double recall3 = queryResults.stream().filter(QueryResult::hit3).count() / n;
        double recall5 = queryResults.stream().filter(QueryResult::hit5).count() / n;
        double recall10 = queryResults.stream().filter(QueryResult::hit10).count() / n;
        double recallProd = queryResults.stream().filter(QueryResult::hitProdSettings).count() / n;
        double avgLatency = queryResults.stream().mapToLong(QueryResult::latencyMs).average().orElse(0);

        Map<String, Double> byDifficulty = new LinkedHashMap<>();
        for (String diff : List.of("easy", "medium", "hard")) {
            List<QueryResult> subset = queryResults.stream().filter(r -> r.difficulty().equals(diff)).toList();
            double dn = subset.size();
            double dRecall5 = dn == 0 ? 0 : subset.stream().filter(QueryResult::hit5).count() / dn;
            byDifficulty.put(diff, dRecall5);
        }

        return new ChunkSizeResult(chunkSize, chunkCount, ingestMs,
            recall1, recall3, recall5, recall10, recallProd, avgLatency, byDifficulty);
    }

    private List<GoldenQuery> loadGoldenSet() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream is = getClass().getResourceAsStream("/rag-eval/golden-set.json")) {
            return List.of(mapper.readValue(is, GoldenQuery[].class));
        }
    }

    private void writeReport(List<ChunkSizeResult> results, int totalQueries) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# RAG 정확도 실험 결과\n\n");
        sb.append("골든셋 ").append(totalQueries).append("건 · 코퍼스: Spring AI 공식 레퍼런스 문서 7페이지 ")
          .append("(Advisors/ChatClient/RAG/VectorStore/ETL/ChatMemory/Tools) · 임베딩: bge-m3\n\n");

        sb.append("## 청크 크기별 Recall@k\n\n");
        sb.append("| 청크 크기 | 청크 수 | 임베딩 소요(ms) | Recall@1 | Recall@3 | Recall@5 | Recall@10 | 운영설정 Recall(top%d,th=%.1f) | 평균 쿼리 지연(ms) |\n"
            .formatted(TOP_K, SIMILARITY_THRESHOLD));
        sb.append("|---|---|---|---|---|---|---|---|---|\n");
        for (ChunkSizeResult r : results) {
            sb.append("| %d %s | %d | %d | %.1f%% | %.1f%% | %.1f%% | %.1f%% | %.1f%% | %.0f |\n".formatted(
                r.chunkSize(), r.chunkSize() == 1500 ? "(현재값)" : "",
                r.chunkCount(), r.ingestMs(),
                r.recallAt1() * 100, r.recallAt3() * 100, r.recallAt5() * 100, r.recallAt10() * 100,
                r.recallProdSettings() * 100,
                r.avgQueryLatencyMs()));
        }

        sb.append("\n## 난이도별 Recall@5\n\n");
        sb.append("| 청크 크기 | easy | medium | hard |\n|---|---|---|---|\n");
        for (ChunkSizeResult r : results) {
            sb.append("| %d | %.1f%% | %.1f%% | %.1f%% |\n".formatted(
                r.chunkSize(),
                r.recallAt5ByDifficulty().getOrDefault("easy", 0.0) * 100,
                r.recallAt5ByDifficulty().getOrDefault("medium", 0.0) * 100,
                r.recallAt5ByDifficulty().getOrDefault("hard", 0.0) * 100));
        }

        sb.append("\n## 측정 방법\n\n");
        sb.append("- Recall@k: 골든셋 쿼리당 정답 문서가 1개뿐이라 Hit Rate@k와 동일 — top-k 검색 결과에 정답 문서의 청크가 하나라도 포함되면 hit\n");
        sb.append("- Recall@1/3/5/10: similarityThreshold=0.0(필터 없이 순수 랭킹) 기준\n");
        sb.append("- 운영설정 Recall: topK=%d, similarityThreshold=%.1f — 실제 배포 중인 SearchRequest 설정과 동일\n"
            .formatted(TOP_K, SIMILARITY_THRESHOLD));

        Path outPath = Path.of("../docs/rag-accuracy-experiment.md");
        Files.writeString(outPath, sb.toString());
        log.info("리포트 작성 완료 — {}", outPath.toAbsolutePath());
    }
}
