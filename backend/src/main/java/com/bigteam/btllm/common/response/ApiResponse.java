package com.bigteam.btllm.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * [역할] 모든 REST API 응답의 공통 래퍼 — 성공·실패 구조 통일
 *
 * [설계 결정사항]
 * - 공통 래퍼 도입 이유: 클라이언트가 응답 구조를 예측 가능하게 처리 가능
 *   success 필드로 단건 분기, code로 상세 에러 식별, data는 성공 시만 존재
 * - @JsonInclude(NON_NULL): data가 null인 에러 응답에서 "data: null" 필드 미노출
 * - Generic<T>: 타입 안전한 data 필드, Object 캐스팅 불필요
 * - 정적 팩토리 메서드: 생성자 직접 호출보다 의미 명확 (success/error 구분)
 */
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

	private final boolean success;
	private final String code;
	private final String message;
	private final LocalDateTime timestamp;
	private final T data;

	private ApiResponse(boolean success, String code, String message, T data) {
		this.success = success;
		this.code = code;
		this.message = message;
		this.timestamp = LocalDateTime.now();
		this.data = data;
	}

	public static <T> ApiResponse<T> ok(T data) {
		return new ApiResponse<>(true, "OK", "성공했습니다.", data);
	}

	public static <T> ApiResponse<T> ok() {
		return new ApiResponse<>(true, "OK", "성공했습니다.", null);
	}

	public static <T> ApiResponse<T> error(String code, String message) {
		return new ApiResponse<>(false, code, message, null);
	}
}