package com.bigteam.btllm.rag.service;

import com.bigteam.btllm.common.exception.BusinessException;
import com.bigteam.btllm.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.jsoup.JsoupDocumentReader;
import org.springframework.ai.reader.jsoup.config.JsoupDocumentReaderConfig;
import org.springframework.ai.model.transformer.KeywordMetadataEnricher;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;

import org.jsoup.Jsoup;
import java.io.IOException;
import java.util.Map;

import java.net.MalformedURLException;
import java.util.List;

/**
 * [역할] ETL(Extract-Transform-Load) 파이프라인 — 문서를 벡터 DB에 적재
 *
 * [설계 결정사항]
 * - 파이프라인 구성:
 *   DocumentReader → TokenTextSplitter → KeywordMetadataEnricher → VectorStore
 * - Reader 선택 기준:
 *   JsoupDocumentReader: 웹 URL (HTML 파싱)
 *   PagePdfDocumentReader: PDF 파일 (Apache PDFBox 기반)
 *   TikaDocumentReader: Word/Excel/TXT 등 범용 (Apache Tika 기반)
 * - TokenTextSplitter 기본값(800 토큰/청크): 임베딩 모델(bge-m3 1024차원)의 입력 한도 이내
 * - KeywordMetadataEnricher: 청크마다 LLM 호출로 키워드 5개 추출 → 메타데이터 저장
 *   [주의] 청크 수만큼 LLM 동기 호출 발생 → 대용량 문서는 느림
 *   Phase 3 포트폴리오 목적에서는 동기 처리로 충분, 운영 시 비동기 큐 필요
 * - 동기 처리: 응답 지연 가능 → 운영 환경에서는 비동기 작업 큐로 전환 필요
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EtlPipelineService {

	private final VectorStore vectorStore;
	private final OllamaChatModel chatModel;

	/**
	 * 웹 URL → Jsoup 직접 크롤링 → 파이프라인
	 *
	 * [설계 결정사항]
	 * - JsoupDocumentReader(UrlResource) 미사용:
	 *   UrlResource는 내부적으로 Java HttpURLConnection 사용 → User-Agent 미설정
	 *   대부분의 웹사이트가 봇 차단(403)으로 응답
	 * - Jsoup.connect() 직접 사용: User-Agent·timeout 설정 가능, 403 방지
	 * - 텍스트 추출 후 Spring AI Document로 수동 변환: JsoupDocumentReader와 동일한 파이프라인 진입
	 */
	public int ingestUrl(String url) {
		try {
			org.jsoup.nodes.Document html = Jsoup.connect(url)
				.userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
				.timeout(15_000)
				.get();

			String text = html.body().text();
			if (text.isBlank()) {
				log.warn("URL 본문 비어있음 (JS 렌더링 페이지일 가능성) — url: {}", url);
				return 0;
			}

			log.info("Jsoup 크롤링 완료 — url: {}, 본문 길이: {} chars", url, text.length());
			Document doc = new Document(text, Map.of("source", url, "type", "web"));
			return pipeline(List.of(doc));

		} catch (IOException e) {
			log.error("URL 크롤링 실패 — url: {}, reason: {}", url, e.getMessage());
			throw new BusinessException(ErrorCode.INVALID_INPUT);
		}
	}

	/**
	 * PDF 파일 → PagePdfDocumentReader → 파이프라인
	 */
	public int ingestPdf(byte[] bytes, String filename) {
		Resource resource = namedByteResource(bytes, filename);
		List<Document> docs = new PagePdfDocumentReader(resource).get();
		docs.forEach(d -> {
			d.getMetadata().put("source", filename);
			d.getMetadata().put("type", "pdf");
		});
		log.info("PDF 읽기 완료 — file: {}, pages: {}", filename, docs.size());
		return pipeline(docs);
	}

	/**
	 * 범용 파일(Word/Excel/TXT 등) → TikaDocumentReader → 파이프라인
	 */
	public int ingestFile(byte[] bytes, String filename) {
		Resource resource = namedByteResource(bytes, filename);
		List<Document> docs = new TikaDocumentReader(resource).get();
		docs.forEach(d -> {
			d.getMetadata().put("source", filename);
			d.getMetadata().put("type", "file");
		});
		log.info("파일 읽기 완료 — file: {}, docs: {}", filename, docs.size());
		return pipeline(docs);
	}

	/**
	 * 공통 변환·적재 단계: Split → KeywordEnrich → VectorStore
	 *
	 * [설계] 단계 분리 이유:
	 * - TokenTextSplitter: 청크 경계가 문장 중간에 생기지 않도록 토큰 단위 분할
	 * - KeywordMetadataEnricher: 검색 정밀도 향상 (키워드 기반 필터링 가능)
	 * - vectorStore.accept(): bge-m3 임베딩 후 pgVector 저장
	 */
	private int pipeline(List<Document> docs) {
		// 1단계: 청크 분할
		List<Document> chunks = new TokenTextSplitter().apply(docs);
		log.debug("청크 분할 완료 — {} → {} chunks", docs.size(), chunks.size());

		// 2단계: 키워드 메타데이터 추출 (청크당 LLM 호출 1회)
		List<Document> enriched = KeywordMetadataEnricher.builder(chatModel)
			.keywordCount(5)
			.build()
			.apply(chunks);

		// 3단계: 임베딩 + pgVector 저장
		vectorStore.accept(enriched);
		log.info("벡터 DB 적재 완료 — {} chunks", enriched.size());
		return enriched.size();
	}

	/**
	 * MultipartFile byte[]를 파일명 보존 ByteArrayResource로 변환
	 *
	 * [주의] TikaDocumentReader는 파일 확장자로 MIME 타입 판단
	 * getFilename() 오버라이드로 원본 파일명 전달 필수
	 */
	private Resource namedByteResource(byte[] bytes, String filename) {
		return new ByteArrayResource(bytes) {
			@Override
			public String getFilename() { return filename; }
		};
	}
}