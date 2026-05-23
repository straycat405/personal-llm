package com.bigteam.btllm.rag.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * [역할] ETL 비동기 작업 진행률 인메모리 추적기
 *
 * [설계 결정사항]
 * - ConcurrentHashMap: @Async 스레드(ETL)와 SSE 폴링 스레드가 동시 접근 → 스레드 안전 필수
 * - 완료/실패 시 SSE가 remove() 호출 → 메모리 자동 정리
 * - DB 미사용: 진행률은 휘발성 정보 (재시작 시 리셋 허용)
 */
@Component
public class EtlProgressTracker {

    public record ProgressInfo(int progress, String message, boolean done, String error) {}

    private final ConcurrentHashMap<String, ProgressInfo> jobs = new ConcurrentHashMap<>();

    // 작업 초기화 — POST 시 즉시 호출
    public void init(String jobId) {
        jobs.put(jobId, new ProgressInfo(0, "시작 중...", false, null));
    }

    // 진행률 갱신 — ETL 각 단계에서 호출
    public void update(String jobId, int progress, String message) {
        jobs.put(jobId, new ProgressInfo(progress, message, false, null));
    }

    // 완료 처리
    public void complete(String jobId, int totalChunks) {
        jobs.put(jobId, new ProgressInfo(100,
            totalChunks + "개 청크 인덱싱 완료", true, null));
    }

    // 오류 처리
    public void fail(String jobId, String error) {
        ProgressInfo cur = jobs.getOrDefault(jobId,
            new ProgressInfo(0, "", false, null));
        jobs.put(jobId, new ProgressInfo(cur.progress(),
            "오류: " + error, true, error));
    }

    // 현재 상태 조회 — SSE 폴링 스레드가 호출
    public ProgressInfo get(String jobId) {
        return jobs.getOrDefault(jobId,
            new ProgressInfo(0, "작업을 찾을 수 없습니다.", true, "NOT_FOUND"));
    }

    // SSE 완료 후 메모리 정리
    public void remove(String jobId) {
        jobs.remove(jobId);
    }
}
