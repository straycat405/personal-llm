package com.bigteam.btllm.rag.controller;

import com.bigteam.btllm.common.jwt.AuthUser;
import com.bigteam.btllm.common.response.ApiResponse;
import com.bigteam.btllm.rag.dto.EtlJobResponse;
import com.bigteam.btllm.rag.dto.EtlSourceResponse;
import com.bigteam.btllm.rag.dto.EtlUrlRequest;
import com.bigteam.btllm.rag.service.EtlPipelineService;
import com.bigteam.btllm.rag.service.EtlProgressTracker;
import com.bigteam.btllm.rag.service.EtlProgressTracker.ProgressInfo;
import com.bigteam.btllm.rag.service.EtlSourceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [역할] ETL 파이프라인 트리거 REST API + SSE 진행률 스트림 + 지식베이스 관리
 *
 * [설계 결정사항]
 * - POST → 202 Accepted + { jobId }: 즉시 반환, ETL은 @Async 스레드에서 처리
 *   이유: 대용량 PDF 처리 시간이 수 분 → 동기 처리는 HTTP 타임아웃 위험
 * - GET /{jobId}/progress → SseEmitter: 서버가 진행률 push (클라이언트 폴링 불필요)
 *   이유: EventSource API가 표준이며 WebSocket보다 단방향 스트리밍에 적합
 * - SSE 엔드포인트 permitAll: EventSource는 커스텀 헤더 미지원 → JWT 헤더 전송 불가
 *   UUID jobId가 충분한 난수성으로 대체 (추측 불가)
 * - SseEmitter 타임아웃 10분: 대용량 문서 처리 여유 시간 확보
 * - 폴링 간격 500ms: 진행률 체감 부드러움 vs 서버 부하 균형
 * - GET /sources, DELETE /sources: JdbcTemplate 직접 SQL (VectorStore 집계 미지원)
 */
@RestController
@RequestMapping("/api/v1/admin/etl")
@RequiredArgsConstructor
public class EtlController {

    private final EtlPipelineService etlPipelineService;
    private final EtlProgressTracker tracker;
    private final EtlSourceService etlSourceService;

    // ── POST: 인덱싱 시작 → 202 + jobId ────────────────────────

    @PostMapping("/url")
    public ResponseEntity<ApiResponse<EtlJobResponse>> ingestUrl(
        @Valid @RequestBody EtlUrlRequest request,
        @AuthenticationPrincipal AuthUser authUser) {

        String jobId = UUID.randomUUID().toString();
        tracker.init(jobId);
        etlPipelineService.ingestUrlAsync(request.url(), jobId, authUser.id());
        return ResponseEntity.accepted().body(ApiResponse.ok(new EtlJobResponse(jobId)));
    }

    @PostMapping("/pdf")
    public ResponseEntity<ApiResponse<EtlJobResponse>> ingestPdf(
        @RequestParam("file") MultipartFile file,
        @AuthenticationPrincipal AuthUser authUser) throws IOException {

        String jobId = UUID.randomUUID().toString();
        tracker.init(jobId);
        etlPipelineService.ingestPdfAsync(
            file.getBytes(),
            file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown.pdf",
            jobId,
            authUser.id()
        );
        return ResponseEntity.accepted().body(ApiResponse.ok(new EtlJobResponse(jobId)));
    }

    @PostMapping("/file")
    public ResponseEntity<ApiResponse<EtlJobResponse>> ingestFile(
        @RequestParam("file") MultipartFile file,
        @AuthenticationPrincipal AuthUser authUser) throws IOException {

        String jobId = UUID.randomUUID().toString();
        tracker.init(jobId);
        etlPipelineService.ingestFileAsync(
            file.getBytes(),
            file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown",
            jobId,
            authUser.id()
        );
        return ResponseEntity.accepted().body(ApiResponse.ok(new EtlJobResponse(jobId)));
    }

    // ── GET: SSE 진행률 스트림 ────────────────────────────────
    // [알려진 한계] permitAll이라 소유권 predicate를 걸 신원 정보가 없다(EventSource는 커스텀
    //   헤더를 못 보낸다). progress 메시지는 파일명·문서 내용을 담지 않고 퍼센트/상태 문구뿐이라
    //   노출 시 피해가 작고, UUID jobId도 추측 불가하다. 신원 있는 SSE(서명 티켓 등)는 P1 과제로
    //   HANDOFF에 남겨둔다 — 이 컨트롤러의 다른 엔드포인트(ingest/list/delete)는 이미 소유권을 강제한다.

    @GetMapping(value = "/{jobId}/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter progress(@PathVariable String jobId) {
        // [설계] 10분 타임아웃: 대용량 문서 처리 시간 여유 확보
        SseEmitter emitter = new SseEmitter(10 * 60 * 1000L);

        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> emitter.complete());

        // [설계] 별도 스레드에서 500ms 폴링: SSE 특성상 응답 스트림을 점유하는 구조
        //   @Async와 달리 SSE 전용 폴링이므로 단순 Thread 사용 (빈 관리 불필요)
        new Thread(() -> {
            try {
                while (true) {
                    ProgressInfo info = tracker.get(jobId);
                    emitter.send(SseEmitter.event()
                        .name("progress")
                        .data(Map.of(
                            "progress", info.progress(),
                            "message",  info.message(),
                            "done",     info.done(),
                            "error",    info.error() != null ? info.error() : ""
                        )));

                    if (info.done()) {
                        tracker.remove(jobId);  // 메모리 정리
                        emitter.complete();
                        return;
                    }
                    Thread.sleep(500);
                }
            } catch (IOException e) {
                // 클라이언트 연결 끊김 — 정상 종료
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        }).start();

        return emitter;
    }

    // ── GET: 인덱싱된 소스 목록 ──────────────────────────────

    @GetMapping("/sources")
    public ResponseEntity<ApiResponse<List<EtlSourceResponse>>> listSources(
            @AuthenticationPrincipal AuthUser authUser) {
        return ResponseEntity.ok(ApiResponse.ok(etlSourceService.listSources(authUser.id())));
    }

    // ── DELETE: source 기준 청크 전체 삭제 ───────────────────
    // [설계] source는 URL 슬래시 포함 가능 → @PathVariable 대신 @RequestParam 사용
    // [보안] 소유권 predicate를 SQL WHERE 절에 함께 걸어, 다른 사용자의 source 이름을 넣어도
    //   0건 삭제로 끝난다 — 존재 여부조차 알려주지 않는다.
    @DeleteMapping("/sources")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> deleteSource(
            @RequestParam String source,
            @AuthenticationPrincipal AuthUser authUser) {
        int deleted = etlSourceService.deleteSource(source, authUser.id());
        return ResponseEntity.ok(ApiResponse.ok(Map.of("deleted", deleted)));
    }
}
