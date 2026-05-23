package com.bigteam.btllm.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// [설계] username 제거: 이메일이 로그인 식별자, username은 서버에서 email prefix로 자동 생성
public record SignupRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8) String password
) {
}
