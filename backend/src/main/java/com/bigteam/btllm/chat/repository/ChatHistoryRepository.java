package com.bigteam.btllm.chat.repository;

import com.bigteam.btllm.chat.entity.ChatHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ChatHistoryRepository extends JpaRepository<ChatHistory, Long> {

	// 채팅방의 전체 대화 이력 (시간순)
	List<ChatHistory> findByChatRoomIdOrderByCreatedAtAsc(Long chatRoomId);

	// 채팅방의 총 토큰 사용량 합산 (TokenTrackingAdvisor 연동용)
	@Query("SELECT SUM(h.totalTokens) FROM ChatHistory h WHERE h.chatRoom.id = :chatRoomId AND h.totalTokens IS NOT NULL")
	Long sumTotalTokensByChatRoomId(@Param("chatRoomId") Long chatRoomId);
}