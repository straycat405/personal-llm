package com.bigteam.btllm.rag.experiment;

import com.bigteam.btllm.rag.service.EtlPipelineService;
import com.bigteam.btllm.rag.service.EtlProgressTracker;
import com.bigteam.btllm.rag.service.EtlSourceService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 평가용 문서를 실제 ETL 경로로 다시 색인하는 운영 유틸리티.
 *
 * [필요한 이유]
 * 요약 청크 생성은 색인 시점에 동작하므로, 기능 추가 이전에 색인된 문서에는 요약 청크가 없다.
 * 골든셋 평가를 같은 조건에서 재실행하려면 기존 청크를 지우고 동일 문서를 다시 넣어야 한다.
 * (실제 서비스에서도 색인 로직이 바뀌면 동일한 재색인 문제가 생긴다.)
 *
 * REST 업로드 대신 파이프라인을 직접 호출하는 이유는 JWT 인증 없이 재현 가능하게 하기 위함이며,
 * 경로는 `ReindexPipelineService.ingestPdfAsync` → 요약 → 임베딩으로 운영과 동일하다.
 *
 * 사용법:
 *   REINDEX_PDF_PATH='C:\경로\문서.pdf' ./gradlew reindexDocument
 */
@SpringBootTest
@Tag("reindex")
class ReindexDocumentTask {

    private static final String PDF_PATH = System.getenv("REINDEX_PDF_PATH");
    private static final long TIMEOUT_MS = 600_000;   // 로컬 모델 요약 포함이라 넉넉히 잡는다

    @Autowired EtlPipelineService etlPipelineService;
    @Autowired EtlProgressTracker tracker;
    @Autowired EtlSourceService etlSourceService;

    @Test
    void reindexDocument() throws Exception {
        assertThat(PDF_PATH)
            .as("REINDEX_PDF_PATH 환경변수로 재색인할 PDF 경로를 지정하세요")
            .isNotBlank();

        Path path = Path.of(PDF_PATH);
        assertThat(Files.exists(path)).as("PDF 파일이 존재해야 합니다: " + PDF_PATH).isTrue();

        String filename = path.getFileName().toString();
        byte[] bytes = Files.readAllBytes(path);

        int deleted = etlSourceService.deleteSource(filename);
        System.out.printf("기존 청크 삭제 — source: %s, %d건%n", filename, deleted);

        String jobId = UUID.randomUUID().toString();
        tracker.init(jobId);
        etlPipelineService.ingestPdfAsync(bytes, filename, jobId);

        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        EtlProgressTracker.ProgressInfo info;
        String lastMessage = "";
        while (true) {
            info = tracker.get(jobId);
            if (!info.message().equals(lastMessage)) {
                System.out.printf("  [%3d%%] %s%n", info.progress(), info.message());
                lastMessage = info.message();
            }
            if (info.done() || System.currentTimeMillis() > deadline) {
                break;
            }
            Thread.sleep(1000);
        }

        assertThat(info.done()).as("색인이 제한 시간 안에 끝나야 합니다").isTrue();
        assertThat(info.error()).as("색인 오류가 없어야 합니다").isNull();

        System.out.println("현재 인덱싱된 문서:");
        etlSourceService.listSources().forEach(source ->
            System.out.printf("  - %s (%d청크)%n", source.source(), source.chunkCount()));
    }
}
