package com.bigteam.btllm.chat.repository;

import com.bigteam.btllm.chat.entity.ChatRoom;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

	// 특정 사용자의 채팅방 목록 (최신순 정렬은 Service에서 처리)
	List<ChatRoom> findByUserId(Long userId);

	// Spring AI MessageChatMemoryAdvisor conversationId로 채팅방 조회
	Optional<ChatRoom> findByConversationId(String conversationId);
}