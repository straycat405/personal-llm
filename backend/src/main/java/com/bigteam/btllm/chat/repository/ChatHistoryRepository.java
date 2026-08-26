package com.bigteam.btllm.chat.repository;

import com.bigteam.btllm.chat.entity.ChatHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ChatHistoryRepository extends JpaRepository<ChatHistory, Long> {

	// 채팅방의 전체 대화 이력 (시간순)
	// [설계] id를 2차 정렬 기준으로 둔 이유: USER 메시지와 ASSISTANT 응답의 createdAt이
	//   같은 시각으로 기록되면(짧은 응답) 순서가 뒤집혀 답변이 질문보다 위에 표시될 수 있다.
	List<ChatHistory> findByChatRoomIdOrderByCreatedAtAscIdAsc(Long chatRoomId);

	// 채팅방의 총 토큰 사용량 합산 (TokenTrackingAdvisor 연동용)
	@Query("SELECT SUM(h.totalTokens) FROM ChatHistory h WHERE h.chatRoom.id = :chatRoomId AND h.totalTokens IS NOT NULL")
	Long sumTotalTokensByChatRoomId(@Param("chatRoomId") Long chatRoomId);

	// [Tool 2] 키워드 검색 — LIKE 쿼리, 대소문자 무시 (PostgreSQL ILIKE 동일 효과)
	@Query("SELECT h FROM ChatHistory h WHERE h.chatRoom.id = :chatRoomId AND LOWER(h.content) LIKE LOWER(CONCAT('%', :keyword, '%')) ORDER BY h.createdAt ASC")
	List<ChatHistory> findByChatRoomIdAndKeyword(@Param("chatRoomId") Long chatRoomId, @Param("keyword") String keyword);
}