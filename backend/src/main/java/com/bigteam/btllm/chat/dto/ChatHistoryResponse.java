package com.bigteam.btllm.chat.dto;

import com.bigteam.btllm.chat.entity.ChatHistory;
import com.bigteam.btllm.chat.entity.MessageRole;

import java.time.LocalDateTime;

// ChatHistory 엔티티 → API 응답 변환용 DTO
public record ChatHistoryResponse(
	Long id,
	MessageRole role,   // USER or ASSISTANT
	String content,
	LocalDateTime createdAt
) {
	// 엔티티 → DTO 변환 팩토리 메서드
	public static ChatHistoryResponse from(ChatHistory h) {
		return new ChatHistoryResponse(h.getId(), h.getRole(), h.getContent(), h.getCreatedAt());
	}
}