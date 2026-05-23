package com.bigteam.btllm.chat.service;

import com.bigteam.btllm.chat.dto.ChatHistoryResponse;
import com.bigteam.btllm.chat.dto.ChatRoomCreateRequest;
import com.bigteam.btllm.chat.dto.ChatRoomResponse;
import com.bigteam.btllm.chat.entity.ChatRoom;
import com.bigteam.btllm.chat.repository.ChatHistoryRepository;
import com.bigteam.btllm.chat.repository.ChatRoomRepository;
import com.bigteam.btllm.common.exception.BusinessException;
import com.bigteam.btllm.common.exception.ErrorCode;
import com.bigteam.btllm.user.entity.User;
import com.bigteam.btllm.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * [역할] 채팅방 생성·조회·삭제 유스케이스 처리
 *
 * [설계 결정사항]
 * - conversationId를 UUID로 생성:
 *   Spring AI MessageChatMemoryAdvisor의 conversationId와 1:1 매핑되는 값
 *   DB PK(Long) 대신 UUID 사용 → 외부 노출 시 순차 ID 추측 공격 방어
 * - 삭제 시 소유자 검증: roomId만으로 삭제 가능하면 타 사용자 채팅방 삭제 가능
 *   userId 일치 여부를 Service에서 명시적으로 검증
 *
 * [TODO] Phase 2: 채팅방 삭제 시 Spring AI JdbcChatMemoryRepository의 해당 conversationId 메모리도 함께 삭제
 */
@Service
@RequiredArgsConstructor
public class ChatRoomService {

	private final ChatRoomRepository chatRoomRepository;
	private final UserRepository userRepository;
	private final ChatHistoryRepository chatHistoryRepository;

	@Transactional
	public ChatRoomResponse create(Long userId, ChatRoomCreateRequest request) {
		User user = userRepository.findById(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

		ChatRoom room = ChatRoom.builder()
			.user(user)
			.title(request.title())
			// [설계] UUID.randomUUID(): Spring AI Advisor의 conversationId 키로 사용
			// 생성 시점에 고정, 이후 변경 없음
			.conversationId(UUID.randomUUID().toString())
			.build();

		return ChatRoomResponse.from(chatRoomRepository.save(room));
	}

	@Transactional(readOnly = true)
	public List<ChatRoomResponse> findAllByUser(Long userId) {
		return chatRoomRepository.findByUserId(userId).stream()
			.map(ChatRoomResponse::from)
			.toList();
	}

	@Transactional
	public void delete(Long userId, Long roomId) {
		ChatRoom room = chatRoomRepository.findById(roomId)
			.orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));

		if (!room.getUser().getId().equals(userId)) {
			throw new BusinessException(ErrorCode.CHAT_ROOM_FORBIDDEN);
		}

		chatRoomRepository.delete(room);
	}

	// 채팅방 대화 이력 조회 — 소유자 검증 후 시간순 반환
	@Transactional(readOnly = true)
	public List<ChatHistoryResponse> findHistories(Long userId, Long roomId) {
		// 소유자 확인: 타 사용자 이력 노출 방지
		ChatRoom room = chatRoomRepository.findById(roomId)
			.orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
		if (!room.getUser().getId().equals(userId)) {
			throw new BusinessException(ErrorCode.CHAT_ROOM_FORBIDDEN);
		}
		// Repository 메서드 이미 존재 (시간순 정렬)
		return chatHistoryRepository.findByChatRoomIdOrderByCreatedAtAsc(roomId)
			.stream()
			.map(ChatHistoryResponse::from)
			.toList();
	}


}