package com.bigteam.btllm.chat.controller;

import com.bigteam.btllm.chat.dto.ChatRoomCreateRequest;
import com.bigteam.btllm.chat.dto.ChatRoomResponse;
import com.bigteam.btllm.chat.service.ChatRoomService;
import com.bigteam.btllm.common.jwt.AuthUser;
import com.bigteam.btllm.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * [역할] 채팅방 REST API — 생성·조회·삭제 엔드포인트
 *
 * [설계 결정사항]
 * - @AuthenticationPrincipal AuthUser: JwtAuthFilter에서 SecurityContext에 주입한 사용자 정보 직접 수신
 *   userId를 PathVariable로 받지 않음 → 토큰 위조로 타 사용자 데이터 접근 불가
 * - 응답을 ApiResponse<T>로 래핑: 클라이언트가 일관된 응답 구조 파싱 가능
 */
@RestController
@RequestMapping("/api/v1/chat-rooms")
@RequiredArgsConstructor
public class ChatRoomController {

	private final ChatRoomService chatRoomService;

	@PostMapping
	public ResponseEntity<ApiResponse<ChatRoomResponse>> create(
		@AuthenticationPrincipal AuthUser authUser,
		@Valid @RequestBody ChatRoomCreateRequest request) {
		return ResponseEntity
			.status(HttpStatus.CREATED)
			.body(ApiResponse.ok(chatRoomService.create(authUser.id(), request)));
	}

	@GetMapping
	public ResponseEntity<ApiResponse<List<ChatRoomResponse>>> findAll(
		@AuthenticationPrincipal AuthUser authUser) {
		return ResponseEntity.ok(ApiResponse.ok(chatRoomService.findAllByUser(authUser.id())));
	}

	@DeleteMapping("/{roomId}")
	public ResponseEntity<ApiResponse<Void>> delete(
		@AuthenticationPrincipal AuthUser authUser,
		@PathVariable Long roomId) {
		chatRoomService.delete(authUser.id(), roomId);
		return ResponseEntity.noContent().build();
	}
}