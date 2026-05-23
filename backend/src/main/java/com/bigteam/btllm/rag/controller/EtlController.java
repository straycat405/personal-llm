package com.bigteam.btllm.rag.controller;

import com.bigteam.btllm.common.response.ApiResponse;
import com.bigteam.btllm.rag.dto.EtlUrlRequest;
import com.bigteam.btllm.rag.service.EtlPipelineService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * [역할] ETL 파이프라인 트리거 REST API (관리자용)
 *
 * [설계 결정사항]
 * - /api/v1/admin/** 경로: 운영 환경에서는 ROLE_ADMIN 권한 분리 필요
 *   Phase 3에서는 인증된 사용자라면 모두 허용 (포트폴리오 시연 목적)
 * - 응답: 적재된 청크 수 반환 → "N개 청크가 벡터 DB에 저장됨" 으로 성공 확인 가능
 * - 동기 처리: 대용량 파일은 HTTP 타임아웃 주의 → 운영 시 비동기 큐 전환
 */
@RestController
@RequestMapping("/api/v1/admin/etl")
@RequiredArgsConstructor
public class EtlController {

	private final EtlPipelineService etlPipelineService;

	/**
	 * 웹 URL 크롤링 후 벡터 DB 적재
	 * POST /api/v1/admin/etl/url
	 * Body: { "url": "https://..." }
	 */
	@PostMapping("/url")
	public ResponseEntity<ApiResponse<Integer>> ingestUrl(
		@Valid @RequestBody EtlUrlRequest request) {
		int chunks = etlPipelineService.ingestUrl(request.url());
		return ResponseEntity.ok(ApiResponse.ok(chunks));
	}

	/**
	 * PDF 파일 업로드 후 벡터 DB 적재
	 * POST /api/v1/admin/etl/pdf (multipart/form-data, field: file)
	 */
	@PostMapping("/pdf")
	public ResponseEntity<ApiResponse<Integer>> ingestPdf(
		@RequestParam("file") MultipartFile file) throws IOException {
		int chunks = etlPipelineService.ingestPdf(
			file.getBytes(),
			file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown.pdf"
		);
		return ResponseEntity.ok(ApiResponse.ok(chunks));
	}

	/**
	 * 범용 파일 업로드 후 벡터 DB 적재 (Word, Excel, TXT 등 Tika 지원 형식)
	 * POST /api/v1/admin/etl/file (multipart/form-data, field: file)
	 */
	@PostMapping("/file")
	public ResponseEntity<ApiResponse<Integer>> ingestFile(
		@RequestParam("file") MultipartFile file) throws IOException {
		int chunks = etlPipelineService.ingestFile(
			file.getBytes(),
			file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown"
		);
		return ResponseEntity.ok(ApiResponse.ok(chunks));
	}
}