package com.bigteam.btllm.rag.experiment;

import com.bigteam.btllm.rag.config.RagSearchSettings;
import com.bigteam.btllm.rag.service.DocumentSummarizer;
import com.bigteam.btllm.rag.service.HybridReranker;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Locale;

/**
 * 요약 청크가 개요형 질의에서 실제로 검색되는지 확인하는 진단 도구.
 *
 * [왜 필요한가]
 * 요약 청크를 추가했는데 골든셋 점수가 오르지 않았다. 이때 원인은 둘 중 하나다.
 *   (A) 요약 청크가 애초에 검색 후보에 들어오지 못한다 → 가설의 전제가 틀림
 *   (B) 검색은 되는데 모델이 활용하지 못한다 → 검색은 고쳤고 남은 건 체급 문제
 * 최종 답변만 보면 이 둘을 구분할 수 없으므로 검색 단계를 직접 들여다본다.
 */
@SpringBootTest
@Tag("reindex")
class SummaryChunkRetrievalDiagnostic {

    /** 골든셋 실행 중 모델이 실제로 만들었던 질의와 개요형 변형들 */
    private static final List<String> QUERIES = List.of(
        "모두의 창업 프로젝트",
        "업로드한 모두의 창업 프로젝트 문서가 어떤 사업인지 핵심만 간략히 설명해줘",
        "이 문서는 어떤 문서야",
        "사업 개요",
        "일반/기술트랙과 로컬트랙 지원 분야 차이"
    );

    @Autowired VectorStore vectorStore;
    @Autowired HybridReranker hybridReranker;

    @Test
    void reportSummaryChunkRank() {
        for (String query : QUERIES) {
            List<Document> candidates = vectorStore.similaritySearch(
                SearchRequest.builder()
                    .query(query)
                    .topK(RagSearchSettings.RERANK_CANDIDATE_K)
                    .similarityThreshold(RagSearchSettings.SIMILARITY_THRESHOLD)
                    .build());
            List<Document> reranked = hybridReranker.rerank(query, candidates, RagSearchSettings.TOP_K);

            System.out.printf("%n=== 질의: %s%n", query);
            System.out.printf("  후보 %d건 / 최종 %d건%n", candidates.size(), reranked.size());
            System.out.printf("  후보 풀 내 요약 청크 순위: %s%n", rankOfSummary(candidates));
            System.out.printf("  재정렬 후 요약 청크 순위: %s%n", rankOfSummary(reranked));

            for (int i = 0; i < candidates.size(); i++) {
                Document document = candidates.get(i);
                System.out.printf(Locale.US, "   후보 %2d위 score=%.4f %s | %s%n",
                    i + 1,
                    document.getScore() == null ? Double.NaN : document.getScore(),
                    isSummary(document) ? "[요약]" : "[본문]",
                    preview(document.getText()));
            }
        }
    }

    private String rankOfSummary(List<Document> documents) {
        for (int i = 0; i < documents.size(); i++) {
            if (isSummary(documents.get(i))) {
                return (i + 1) + "위";
            }
        }
        return "없음";
    }

    private boolean isSummary(Document document) {
        return DocumentSummarizer.CHUNK_TYPE_SUMMARY.equals(
            String.valueOf(document.getMetadata().get(DocumentSummarizer.CHUNK_TYPE_KEY)));
    }

    private String preview(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").strip();
        return normalized.length() <= 60 ? normalized : normalized.substring(0, 60) + "...";
    }
}
