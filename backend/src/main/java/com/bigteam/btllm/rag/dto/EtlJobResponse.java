package com.bigteam.btllm.rag.dto;

// POST /etl/** 202 응답 DTO — 클라이언트가 jobId로 SSE 구독
public record EtlJobResponse(String jobId) {}
