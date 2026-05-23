package com.bigteam.btllm.user.controller;

import com.bigteam.btllm.common.response.ApiResponse;
import com.bigteam.btllm.user.dto.LoginRequest;
import com.bigteam.btllm.user.dto.LoginResponse;
import com.bigteam.btllm.user.dto.SignupRequest;
import com.bigteam.btllm.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * [역할] 회원가입·로그인 REST API
 *
 * [설계 결정사항]
 * - 로그인 성공 시 200 반환 (201 아님):
 *   로그인은 리소스 생성이 아닌 토큰 발급 행위 → RFC 상 200이 적합
 * - 회원가입 성공 시 201 반환: 사용자 리소스 생성 → Created
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

	private final UserService userService;

	@PostMapping("/signup")
	public ResponseEntity<ApiResponse<Void>> signup(@Valid @RequestBody SignupRequest request) {
		userService.signup(request);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok());
	}

	@PostMapping("/login")
	public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(userService.login(request)));
	}
}