package com.bigteam.btllm.chat.dto;

import com.bigteam.btllm.chat.entity.ChatRoom;

import java.time.LocalDateTime;

/**
 * [역할] 채팅방 응답 DTO — Entity 직접 노출 대신 필요한 필드만 선별
 *
 * [설계 결정사항]
 * - conversationId 포함: 클라이언트가 WebSocket 연결 시 conversationId를 함께 전송
 *   Spring AI MessageChatMemoryAdvisor가 이 값으로 대화 맥락을 구분·유지
 */
public record ChatRoomResponse(
	Long id,
	String title,
	String conversationId,
	LocalDateTime createdAt,
	LocalDateTime updatedAt
) {
	public static ChatRoomResponse from(ChatRoom room) {
		return new ChatRoomResponse(
			room.getId(),
			room.getTitle(),
			room.getConversationId(),
			room.getCreatedAt(),
			room.getUpdatedAt()
		);
	}
}