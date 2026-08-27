package com.bigteam.btllm.chat.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * [인덱스 설계] chat_room_id는 FK지만 PostgreSQL은 FK에 인덱스를 자동 생성하지 않는다.
 *   인덱스가 없던 상태에서는 방 하나(1,000행)를 읽으려고 20만 행 전체를 Seq Scan하고
 *   199,036행을 필터로 버렸다(실측 11.9ms / 2,826 buffers).
 *
 *   정렬 키(created_at, id)까지 인덱스에 포함시킨 이유: 조회 쿼리가 항상
 *   ORDER BY created_at, id 로 끝나므로, 인덱스 순서가 정렬 순서와 같으면 Sort 노드
 *   자체가 사라지고 LIMIT이 조기 종료로 이어진다(0.21ms / 8 buffers).
 *
 *   본문 LIKE '%키워드%' 검색용 trigram GIN 인덱스는 표현식 인덱스라 JPA로 선언할 수
 *   없어 db/index/chat_histories_trgm.sql 로 분리했다. 근거는 그 파일 주석 참고.
 */
@Entity
@Table(name = "chat_histories", indexes = {
	@Index(name = "ix_chat_histories_room_created", columnList = "chat_room_id, created_at, id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class ChatHistory {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "chat_room_id", nullable = false)
	private ChatRoom chatRoom;

	// 메시지 발신자 구분 (USER or ASSISTANT)
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private MessageRole role;

	@Column(nullable = false, columnDefinition = "TEXT")
	private String content;

	// 토큰 사용량: TokenTrackingAdvisor가 ASSISTANT 메시지 저장 시 기록
	@Column
	private Integer promptTokens;

	@Column
	private Integer completionTokens;

	@Column
	private Integer totalTokens;

	@CreatedDate
	@Column(updatable = false)
	private LocalDateTime createdAt;

	@Builder
	public ChatHistory(ChatRoom chatRoom, MessageRole role, String content,
		Integer promptTokens, Integer completionTokens, Integer totalTokens) {
		this.chatRoom = chatRoom;
		this.role = role;
		this.content = content;
		this.promptTokens = promptTokens;
		this.completionTokens = completionTokens;
		this.totalTokens = totalTokens;
	}
}