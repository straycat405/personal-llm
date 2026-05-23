package com.bigteam.btllm.common.exception;

import lombok.Getter;

/**
 * [역할] 비즈니스 규칙 위반 시 발생하는 도메인 예외
 *
 * [설계 결정사항]
 * - IllegalArgumentException 대신 커스텀 예외 사용:
 *   IllegalArgumentException은 Java 표준 예외라 의미가 모호하고 HTTP 상태코드 매핑 불가
 *   BusinessException은 ErrorCode를 품고 있어 GlobalExceptionHandler에서 상태코드 자동 결정
 * - RuntimeException 상속: 트랜잭션 롤백 자동 처리 (@Transactional 기본 동작)
 *   CheckedException 사용 시 모든 호출부에 throws 선언 강제 → 불필요한 보일러플레이트
 */
@Getter
public class BusinessException extends RuntimeException {

	private final ErrorCode errorCode;

	public BusinessException(ErrorCode errorCode) {
		super(errorCode.getMessage());
		this.errorCode = errorCode;
	}
}