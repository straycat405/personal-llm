package com.bigteam.btllm.rag.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HybridRerankerTest {

    private final HybridReranker reranker = new HybridReranker();

    @Test
    void 키워드가_겹치는_후순위_후보를_끌어올린다() {
        // 벡터 점수는 distractor가 더 높지만(0.60 > 0.55), 질의 키워드는 정답 청크에만 있다.
        Document distractor = Document.builder()
            .text("중복참여 불가 사업 목록: 청년창업사관학교, 예비창업패키지")
            .score(0.60)
            .build();
        Document answer = Document.builder()
            .text("모두의 창업 프로젝트는 일반트랙과 로컬트랙으로 나뉘며 창업활동자금을 지원합니다")
            .score(0.55)
            .build();

        List<Document> reranked = reranker.rerank(
            "모두의 창업 프로젝트 트랙 구성이 뭐야", List.of(distractor, answer), 2);

        assertThat(reranked.get(0).getText()).isEqualTo(answer.getText());
    }

    @Test
    void topK만큼만_반환한다() {
        Document a = Document.builder().text("a 내용").score(0.9).build();
        Document b = Document.builder().text("b 내용").score(0.8).build();
        Document c = Document.builder().text("c 내용").score(0.7).build();

        List<Document> reranked = reranker.rerank("질의", List.of(a, b, c), 2);

        assertThat(reranked).hasSize(2);
    }

    @Test
    void 질의_토큰이_없으면_벡터_순서를_유지한다() {
        Document a = Document.builder().text("내용 A").score(0.9).build();
        Document b = Document.builder().text("내용 B").score(0.8).build();

        List<Document> reranked = reranker.rerank("", List.of(a, b), 2);

        assertThat(reranked).containsExactly(a, b);
    }
}
