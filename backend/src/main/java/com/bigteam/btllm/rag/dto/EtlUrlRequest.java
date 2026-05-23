package com.bigteam.btllm.rag.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * [역할] URL 기반 ETL 요청 DTO
 */
public record EtlUrlRequest(
	@NotBlank(message = "URL은 필수입니다") String url
) {}