package com.bigteam.btllm.chat.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "chat_histories")
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