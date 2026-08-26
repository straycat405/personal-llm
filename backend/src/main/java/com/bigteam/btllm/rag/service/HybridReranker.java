package com.bigteam.btllm.rag.service;

import com.bigteam.btllm.rag.config.RagSearchSettings;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * [역할] 벡터 유사도만으로 정렬된 검색 후보를 질의-청크 키워드 겹침으로 재정렬한다.
 *
 * [설계 결정사항]
 * - cross-encoder reranker(bge-reranker-v2-m3 등)를 쓰지 않는 이유:
 *   Ollama는 /api/rerank를 지원하지 않아 별도 llama.cpp 서버가 필요하고,
 *   8GB VRAM에 3번째 모델(qwen3:8b + bge-m3 다음)을 얹으면 VRAM 포화로 인한
 *   생성 속도 급락(실측 0.86 t/s) 재현 위험이 크다. 새 인프라 없이 순수 계산으로
 *   먼저 효과를 검증하고, 부족하면 CPU 서빙 cross-encoder로 확장한다.
 * - 형태소 분석기 없이 공백/구두점 기준 토큰화만 사용 — 완전한 형태소 일치는 아니지만
 *   부록·목록처럼 질문과 무관한 distractor 청크를 가려내는 데는 충분하다는 가설로 시작한다.
 * - 벡터 점수(0.7) 비중을 키워드 점수(0.3)보다 높게 둔 이유: 벡터 검색이 이미 상위권에
 *   정답을 올려둔 사례(1위 0.671, 2위 0.665)를 확인했으므로, 키워드 점수는 동점 근처
 *   후보의 순서를 미세 조정하는 보조 신호로만 쓴다.
 */
@Component
public class HybridReranker {

    private static final Pattern TOKEN_DELIMITER = Pattern.compile("[\\s,.·/()\\[\\]{}~\\-:;\"'!?]+");
    private static final int MIN_TOKEN_LENGTH = 2;

    public List<Document> rerank(String query, List<Document> candidates, int topK) {
        Set<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) {
            return candidates.stream().limit(topK).toList();
        }

        return candidates.stream()
            .sorted(Comparator.comparingDouble(
                (Document document) -> combinedScore(document, queryTokens)).reversed())
            .limit(topK)
            .toList();
    }

    private double combinedScore(Document document, Set<String> queryTokens) {
        double vectorScore = document.getScore() != null ? document.getScore() : 0.0;
        double keywordScore = keywordOverlapRatio(document.getText(), queryTokens);
        return RagSearchSettings.RERANK_VECTOR_WEIGHT * vectorScore
            + RagSearchSettings.RERANK_KEYWORD_WEIGHT * keywordScore;
    }

    private double keywordOverlapRatio(String text, Set<String> queryTokens) {
        if (text == null || text.isBlank()) {
            return 0.0;
        }
        String normalizedText = text.toLowerCase();
        long matched = queryTokens.stream().filter(normalizedText::contains).count();
        return (double) matched / queryTokens.size();
    }

    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : TOKEN_DELIMITER.split(text.toLowerCase())) {
            if (token.length() >= MIN_TOKEN_LENGTH) {
                tokens.add(token);
            }
        }
        return tokens;
    }
}
