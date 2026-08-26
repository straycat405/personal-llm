package com.bigteam.btllm.rag.service;

import com.bigteam.btllm.common.exception.BusinessException;
import com.bigteam.btllm.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import org.jsoup.Jsoup;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.Objects;

/**
 * [역할] ETL(Extract-Transform-Load) 파이프라인 — 문서를 벡터 DB에 적재
 *
 * [설계 결정사항]
 * - 파이프라인 구성:
 *   DocumentReader → TokenTextSplitter → KeywordMetadataEnricher → VectorStore
 * - @Async 메서드: 컨트롤러가 즉시 202 반환 후 비동기 처리 시작
 *   → EtlProgressTracker로 진행률 추적, SSE로 클라이언트에 실시간 전달
 * - KeywordMetadataEnricher: 청크 단위 루프 호출 (배치 아님)
 *   → 청크별 진행률 갱신 가능 (10~90% 구간이 가장 긴 구간)
 * - 청크 크기는 `btllm.rag.chunk-size` 설정값(기본 800). 한때 1500을 썼으나 한국어 공고문에서
 *   평균 5,425자 청크가 만들어져 topK=3 근거가 num_ctx 4096을 초과했다(2026-08-27 실측).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EtlPipelineService {

    private final VectorStore vectorStore;
    private final EtlProgressTracker tracker;
    private final DocumentSummarizer documentSummarizer;  // 개요형 질의용 요약 청크 생성

    // [설계] 청크 크기를 설정값으로 뺀 이유: 색인 시점 파라미터라 값을 바꾸려면 재색인이 필요하고,
    //   골든셋으로 크기별 효과를 비교하려면 코드 수정 없이 주입할 수 있어야 한다.
    //   기본값 800은 "topK=3 근거가 num_ctx 4096을 넘지 않게" 하려는 값이다(2026-08-27 실측 참고).
    @Value("${btllm.rag.chunk-size:800}")
    private int chunkSize;

    // ── 비동기 진입점 (@Async) ────────────────────────────────
    // 컨트롤러에서 호출 → Spring AOP 프록시를 통해 별도 스레드에서 실행

    @Async
    public void ingestUrlAsync(String url, String jobId) {
        try {
            tracker.update(jobId, 2, "URL 크롤링 중...");
            org.jsoup.nodes.Document html = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .timeout(15_000)
                .get();

            String text = html.body().text();
            if (text.isBlank()) {
                tracker.fail(jobId, "URL 본문이 비어있습니다 (JS 렌더링 페이지일 수 있음)");
                return;
            }
            log.info("Jsoup 크롤링 완료 — url: {}, 본문 길이: {} chars", url, text.length());
            tracker.update(jobId, 5, "크롤링 완료");

            Document doc = new Document(text, Map.of("source", url, "type", "web"));
            pipelineWithProgress(List.of(doc), jobId);

        } catch (IOException e) {
            log.error("URL 크롤링 실패 — url: {}, reason: {}", url, e.getMessage());
            tracker.fail(jobId, "URL 접근 실패: " + e.getMessage());
        } catch (Exception e) {
            log.error("URL 비동기 인덱싱 중 예외", e);
            tracker.fail(jobId, e.getMessage());
        }
    }

    @Async
    public void ingestPdfAsync(byte[] bytes, String filename, String jobId) {
        try {
            tracker.update(jobId, 2, "PDF 읽는 중...");
            Resource resource = namedByteResource(bytes, filename);
            List<Document> docs = new PagePdfDocumentReader(resource).get();
            docs.forEach(d -> {
                d.getMetadata().put("source", filename);
                d.getMetadata().put("type", "pdf");
            });
            log.info("PDF 읽기 완료 — file: {}, pages: {}", filename, docs.size());
            tracker.update(jobId, 5, "파일 읽기 완료 (" + docs.size() + "페이지)");

            pipelineWithProgress(docs, jobId);

        } catch (Exception e) {
            log.error("PDF 비동기 인덱싱 실패 — file: {}", filename, e);
            tracker.fail(jobId, e.getMessage());
        }
    }

    @Async
    public void ingestFileAsync(byte[] bytes, String filename, String jobId) {
        try {
            tracker.update(jobId, 2, "파일 읽는 중...");
            Resource resource = namedByteResource(bytes, filename);
            List<Document> docs = new TikaDocumentReader(resource).get();
            docs.forEach(d -> {
                d.getMetadata().put("source", filename);
                d.getMetadata().put("type", "file");
            });
            log.info("파일 읽기 완료 — file: {}, docs: {}", filename, docs.size());
            tracker.update(jobId, 5, "파일 읽기 완료");

            pipelineWithProgress(docs, jobId);

        } catch (Exception e) {
            log.error("파일 비동기 인덱싱 실패 — file: {}", filename, e);
            tracker.fail(jobId, e.getMessage());
        }
    }

    // ── 공통 변환·적재 (진행률 포함) ─────────────────────────

    /**
     * Split → VectorStore (KeywordMetadataEnricher 제거)
     *
     * [설계] KeywordMetadataEnricher 제거 이유:
     * - 청크당 LLM 1회 호출 → 30청크 = 30회 순차 호출 = 최대 수 분 소요
     * - RAG 검색 정밀도보다 인덱싱 속도 우선 (포트폴리오 데모 환경)
     * - 키워드 메타데이터 없이도 bge-m3 벡터 유사도 검색으로 충분한 품질
     */
    private void pipelineWithProgress(List<Document> docs, String jobId) {
        // 1단계: 청크 분할 (5 → 25%)
        List<Document> chunks = new TokenTextSplitter(chunkSize, 200, 5, 10000, true,
            List.of('.', '?', '!', '\n')).apply(docs);
        int avgChars = chunks.isEmpty() ? 0
            : chunks.stream().mapToInt(chunk -> chunk.getText().length()).sum() / chunks.size();
        // [설계] 평균 청크 길이를 로그로 남기는 이유: 청크 크기(토큰)와 실제 문자 수의 관계는
        //   언어·문서마다 다르다. 한국어 공고문에서 1500토큰 설정이 평균 5,425자를 만들어
        //   topK=3 근거가 num_ctx 4096을 초과하던 문제를 뒤늦게 발견한 전례가 있다.
        log.info("청크 분할 완료 — {} docs → {} chunks (설정 {}토큰, 평균 {}자)",
            docs.size(), chunks.size(), chunkSize, avgChars);
        tracker.update(jobId, 25, "청크 분할 완료 (" + chunks.size() + "개)");

        // 2단계: 문서 개요 요약 청크 생성 (25 → 40%)
        // [설계] 개요형 질문("이거 무슨 문서야?")은 본문 청크와 의미적으로 멀어 벡터 검색
        //        후보에 정답이 들어오지 못하는 Recall 실패가 실측으로 확인됐다.
        //        문서 전체를 요약한 청크를 함께 색인해 개요형 질의에 가까운 후보를 보장한다.
        //        LLM 1회 호출이라 색인 시간이 늘지만, 색인은 비동기이고 진행률이 노출된다.
        tracker.update(jobId, 25, "문서 개요 요약 중...");
        List<Document> summaries = documentSummarizer.summarize(docs, resolveSource(docs));

        List<Document> toIndex = new ArrayList<>(chunks);
        toIndex.addAll(summaries);
        tracker.update(jobId, 40, summaries.isEmpty()
            ? "임베딩 시작 (" + chunks.size() + "개 청크)"
            : "개요 요약 완료 — 임베딩 시작 (" + chunks.size() + "개 청크 + 개요 1개)");

        // 3단계: bge-m3 임베딩 + pgVector 저장 (40 → 100%)
        vectorStore.accept(toIndex);
        log.info("벡터 DB 적재 완료 — 본문 {} chunks + 요약 {} chunks",
            chunks.size(), summaries.size());
        tracker.complete(jobId, toIndex.size());
    }

    /** 청크 메타데이터에 기록된 출처(파일명·URL)를 요약 프롬프트에 전달하기 위해 꺼낸다. */
    private String resolveSource(List<Document> docs) {
        return docs.stream()
            .map(document -> document.getMetadata().get("source"))
            .filter(Objects::nonNull)
            .map(String::valueOf)
            .findFirst()
            .orElse("업로드한 문서");
    }

    // ── 파일명 보존 ByteArrayResource ────────────────────────
    // [주의] TikaDocumentReader는 getFilename()으로 MIME 타입 판단 → 오버라이드 필수
    private Resource namedByteResource(byte[] bytes, String filename) {
        return new ByteArrayResource(bytes) {
            @Override
            public String getFilename() { return filename; }
        };
    }
}
