package com.bigteam.btllm.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * [역할] 채팅방 생성 요청 DTO
 */
public record ChatRoomCreateRequest(
	@NotBlank @Size(max = 200) String title
) {
}