package com.bigteam.btllm.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * [역할] 도메인별 에러 코드 정의 — HTTP 상태코드 + 에러 식별자 + 메시지 통합 관리
 *
 * [설계 결정사항]
 * - Enum으로 에러 코드 중앙화: 코드·메시지 산발 방지, 클라이언트와 에러 코드 계약 명확화
 * - HttpStatus를 Enum에 포함: GlobalExceptionHandler에서 매핑 로직 불필요
 * - 네이밍 규칙: {도메인}_{원인} (api-response.md 컨벤션 준수)
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

	// ── 공통 ──────────────────────────────────────────
	INVALID_INPUT(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "입력값이 올바르지 않습니다."),
	INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "서버 오류가 발생했습니다."),

	// ── 인증 ──────────────────────────────────────────
	USER_ALREADY_EXISTS(HttpStatus.CONFLICT, "USER_ALREADY_EXISTS", "이미 사용 중인 아이디입니다."),
	INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "아이디 또는 비밀번호가 올바르지 않습니다."),
	USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다."),

	// ── 채팅방 ─────────────────────────────────────────
	CHAT_ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "CHAT_ROOM_NOT_FOUND", "채팅방을 찾을 수 없습니다."),
	CHAT_ROOM_FORBIDDEN(HttpStatus.FORBIDDEN, "CHAT_ROOM_FORBIDDEN", "채팅방에 대한 권한이 없습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}