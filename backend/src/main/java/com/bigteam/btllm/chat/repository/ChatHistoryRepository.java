package com.bigteam.btllm.chat.repository;

import com.bigteam.btllm.chat.entity.ChatHistory;
import org.springframework.data.domain.Pageable;
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
	//
	// [변경] Pageable을 받도록 바꾼 이유: 호출부(LlmTools)가 전체 결과를 받아온 뒤
	//   Java 스트림에서 .limit(5)로 잘라내고 있었다. 버릴 행까지 DB가 정렬해 전송하는
	//   구조라, 매칭이 많은 방일수록 낭비가 그대로 커진다.
	//   LIMIT을 SQL로 내리면 ix_chat_histories_room_created 인덱스가 정렬 순서대로
	//   읽다가 필요한 건수를 채우는 즉시 멈춘다(20만 행 기준 16.0ms → 0.05ms).
	@Query("SELECT h FROM ChatHistory h WHERE h.chatRoom.id = :chatRoomId AND LOWER(h.content) LIKE LOWER(CONCAT('%', :keyword, '%')) ORDER BY h.createdAt ASC")
	List<ChatHistory> findByChatRoomIdAndKeyword(@Param("chatRoomId") Long chatRoomId,
	                                             @Param("keyword") String keyword,
	                                             Pageable pageable);
}