package com.bigteam.btllm.common.jwt;

// [설계] username → email: 이메일 로그인 방식 전환 + OAuth 연동 대비
public record AuthUser(Long id, String email) {
}
