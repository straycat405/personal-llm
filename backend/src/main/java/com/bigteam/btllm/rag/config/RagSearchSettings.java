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

    private RagSearchSettings() {
    }
}
