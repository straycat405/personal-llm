package com.bigteam.btllm.common.exception;

import com.bigteam.btllm.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * [역할] 전역 예외 처리 — Controller 계층 예외를 ApiResponse 형식으로 변환
 *
 * [설계 결정사항]
 * - @RestControllerAdvice: @ControllerAdvice + @ResponseBody 결합
 *   모든 Controller에 걸쳐 예외를 가로채 JSON 응답으로 직렬화
 * - BusinessException 전용 핸들러: ErrorCode에서 HttpStatus 직접 추출
 *   예외 추가 시 이 핸들러는 수정 불필요, ErrorCode만 추가
 * - @Valid 검증 실패(MethodArgumentNotValidException) 별도 처리:
 *   여러 필드 오류를 첫 번째 메시지로 요약 — 클라이언트에 구체적 피드백 제공
 *
 * [주의] 500 에러 로그는 error 레벨 유지: 운영 모니터링 알림 기준
 * 비즈니스 예외(4xx)는 warn 이하로 처리해 노이즈 방지
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
		log.warn("BusinessException: {}", e.getMessage());
		ErrorCode errorCode = e.getErrorCode();
		return ResponseEntity
			.status(errorCode.getHttpStatus())
			.body(ApiResponse.error(errorCode.getCode(), errorCode.getMessage()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException e) {
		// [설계] 첫 번째 필드 오류 메시지만 반환: 클라이언트가 한 번에 하나씩 수정하도록 유도
		String message = e.getBindingResult().getFieldErrors().stream()
			.findFirst()
			.map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
			.orElse("입력값이 올바르지 않습니다.");
		return ResponseEntity
			.badRequest()
			.body(ApiResponse.error(ErrorCode.INVALID_INPUT.getCode(), message));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
		log.error("Unhandled exception", e);
		// [주의] 스택 트레이스를 응답에 절대 포함하지 않음 — 내부 구조 노출 방지
		return ResponseEntity
			.internalServerError()
			.body(ApiResponse.error(
				ErrorCode.INTERNAL_SERVER_ERROR.getCode(),
				ErrorCode.INTERNAL_SERVER_ERROR.getMessage()
			));
	}
}