package com.bigteam.btllm.rag.dto;

// [역할] 지식베이스 목록 API 응답 — source 기준 청크 집계 결과
public record EtlSourceResponse(
    String source,    // 파일명 또는 크롤링 URL
    String type,      // "pdf" | "web" | "file"
    int chunkCount    // 해당 source의 벡터 청크 수
) {}
