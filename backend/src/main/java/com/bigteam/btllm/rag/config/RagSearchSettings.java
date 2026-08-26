package com.bigteam.btllm.rag.config;

/**
 * 운영 RAG 검색 설정의 단일 기준점.
 *
 * LLM Tool과 정확도 실험이 서로 다른 topK를 사용해 결과 해석이 어긋나지 않도록
 * 공통 상수로 관리한다. 런타임 사용자 설정이 필요해지면 ConfigurationProperties로 전환한다.
 */
public final class RagSearchSettings {

    public static final int TOP_K = 3;
    public static final double SIMILARITY_THRESHOLD = 0.5;

    // [설계] 하이브리드 재정렬(키워드+벡터) 후보 풀 — 최종 TOP_K(3)보다 넓게 가져와
    // 벡터 유사도만으로 놓친 키워드 일치 청크를 재정렬 단계에서 끌어올릴 여지를 준다.
    // 새 모델(cross-encoder)을 GPU에 추가로 상주시키지 않고 순수 Java 계산으로 처리한다
    // (Ollama가 /api/rerank를 지원하지 않고, 8GB VRAM에 3번째 모델을 얹는 리스크를 피하려는 결정).
    public static final int RERANK_CANDIDATE_K = 10;
    public static final double RERANK_VECTOR_WEIGHT = 0.7;
    public static final double RERANK_KEYWORD_WEIGHT = 0.3;

    private RagSearchSettings() {
    }
}
