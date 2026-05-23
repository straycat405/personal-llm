package com.bigteam.btllm.chat.service;

import com.bigteam.btllm.chat.dto.ChatHistoryResponse;
import com.bigteam.btllm.chat.dto.ChatRoomCreateRequest;
import com.bigteam.btllm.chat.dto.ChatRoomResponse;
import com.bigteam.btllm.chat.entity.ChatHistory;
import com.bigteam.btllm.chat.entity.ChatRoom;
import com.bigteam.btllm.chat.entity.MessageRole;
import com.bigteam.btllm.chat.repository.ChatHistoryRepository;
import com.bigteam.btllm.chat.repository.ChatRoomRepository;
import com.bigteam.btllm.common.exception.BusinessException;
import com.bigteam.btllm.common.exception.ErrorCode;
import com.bigteam.btllm.user.entity.User;
import com.bigteam.btllm.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatRoomService 단위 테스트")
class ChatRoomServiceTest {

    @Mock private ChatRoomRepository chatRoomRepository;
    @Mock private UserRepository userRepository;
    @Mock private ChatHistoryRepository chatHistoryRepository;

    @InjectMocks
    private ChatRoomService chatRoomService;

    // ── 공통 픽스처 ──────────────────────────────────────────────
    // [설계] @NoArgsConstructor(PROTECTED) + JPA 생성 id 필드 → ReflectionTestUtils로 주입
    private User user;
    private ChatRoom room;

    @BeforeEach
    void setUp() {
        user = User.builder()
            .email("test@test.com")
            .password("encoded-password")
            .username("test")
            .build();
        ReflectionTestUtils.setField(user, "id", 1L);

        room = ChatRoom.builder()
            .user(user)
            .title("테스트 채팅방")
            .conversationId("conv-uuid-1234")
            .build();
        ReflectionTestUtils.setField(room, "id", 1L);
    }

    // ── create() ─────────────────────────────────────────────────
    @Nested
    @DisplayName("create()")
    class Create {

        @Test
        @DisplayName("정상 생성 — 채팅방 저장 후 응답 반환")
        void success() {
            // given
            given(userRepository.findById(1L)).willReturn(Optional.of(user));
            given(chatRoomRepository.save(any(ChatRoom.class))).willReturn(room);

            // when
            ChatRoomResponse result = chatRoomService.create(1L, new ChatRoomCreateRequest("테스트 채팅방"));

            // then
            assertThat(result.title()).isEqualTo("테스트 채팅방");
            assertThat(result.conversationId()).isEqualTo("conv-uuid-1234");
            then(chatRoomRepository).should().save(any(ChatRoom.class));
        }

        @Test
        @DisplayName("사용자 없음 → USER_NOT_FOUND 예외")
        void userNotFound() {
            // given
            given(userRepository.findById(99L)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> chatRoomService.create(99L, new ChatRoomCreateRequest("방")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
        }
    }

    // ── findAllByUser() ───────────────────────────────────────────
    @Nested
    @DisplayName("findAllByUser()")
    class FindAllByUser {

        @Test
        @DisplayName("목록 조회 — 해당 사용자 채팅방 전체 반환")
        void success() {
            // given
            ChatRoom room2 = ChatRoom.builder()
                .user(user)
                .title("두 번째 방")
                .conversationId("conv-uuid-5678")
                .build();
            ReflectionTestUtils.setField(room2, "id", 2L);

            given(chatRoomRepository.findByUserId(1L)).willReturn(List.of(room, room2));

            // when
            List<ChatRoomResponse> result = chatRoomService.findAllByUser(1L);

            // then
            assertThat(result).hasSize(2);
            assertThat(result).extracting(ChatRoomResponse::title)
                .containsExactly("테스트 채팅방", "두 번째 방");
        }

        @Test
        @DisplayName("채팅방 없음 — 빈 리스트 반환")
        void emptyList() {
            // given
            given(chatRoomRepository.findByUserId(1L)).willReturn(List.of());

            // when
            List<ChatRoomResponse> result = chatRoomService.findAllByUser(1L);

            // then
            assertThat(result).isEmpty();
        }
    }

    // ── delete() ─────────────────────────────────────────────────
    @Nested
    @DisplayName("delete()")
    class Delete {

        @Test
        @DisplayName("정상 삭제 — 소유자 일치 시 삭제 호출")
        void success() {
            // given
            given(chatRoomRepository.findById(1L)).willReturn(Optional.of(room));

            // when
            chatRoomService.delete(1L, 1L);

            // then
            then(chatRoomRepository).should().delete(room);
        }

        @Test
        @DisplayName("방 없음 → CHAT_ROOM_NOT_FOUND 예외")
        void roomNotFound() {
            // given
            given(chatRoomRepository.findById(99L)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> chatRoomService.delete(1L, 99L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND);
        }

        @Test
        @DisplayName("소유자 불일치 → CHAT_ROOM_FORBIDDEN 예외")
        void forbidden() {
            // given: 방 소유자 userId=1, 요청자 userId=2
            given(chatRoomRepository.findById(1L)).willReturn(Optional.of(room));

            // when & then
            assertThatThrownBy(() -> chatRoomService.delete(2L, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_ROOM_FORBIDDEN);
        }
    }

    // ── findHistories() ───────────────────────────────────────────
    @Nested
    @DisplayName("findHistories()")
    class FindHistories {

        @Test
        @DisplayName("정상 조회 — 시간순 이력 반환 및 DTO 변환 검증")
        void success() {
            // given
            ChatHistory h1 = ChatHistory.builder()
                .chatRoom(room)
                .role(MessageRole.USER)
                .content("안녕하세요")
                .build();
            ChatHistory h2 = ChatHistory.builder()
                .chatRoom(room)
                .role(MessageRole.ASSISTANT)
                .content("안녕하세요! 무엇을 도와드릴까요?")
                .build();
            ReflectionTestUtils.setField(h1, "id", 1L);
            ReflectionTestUtils.setField(h1, "createdAt", LocalDateTime.now().minusMinutes(1));
            ReflectionTestUtils.setField(h2, "id", 2L);
            ReflectionTestUtils.setField(h2, "createdAt", LocalDateTime.now());

            given(chatRoomRepository.findById(1L)).willReturn(Optional.of(room));
            given(chatHistoryRepository.findByChatRoomIdOrderByCreatedAtAsc(1L))
                .willReturn(List.of(h1, h2));

            // when
            List<ChatHistoryResponse> result = chatRoomService.findHistories(1L, 1L);

            // then
            assertThat(result).hasSize(2);
            assertThat(result.get(0).role()).isEqualTo(MessageRole.USER);
            assertThat(result.get(0).content()).isEqualTo("안녕하세요");
            assertThat(result.get(1).role()).isEqualTo(MessageRole.ASSISTANT);
            assertThat(result.get(1).content()).isEqualTo("안녕하세요! 무엇을 도와드릴까요?");
        }

        @Test
        @DisplayName("이력 없음 — 빈 리스트 반환")
        void emptyHistory() {
            // given
            given(chatRoomRepository.findById(1L)).willReturn(Optional.of(room));
            given(chatHistoryRepository.findByChatRoomIdOrderByCreatedAtAsc(1L))
                .willReturn(List.of());

            // when
            List<ChatHistoryResponse> result = chatRoomService.findHistories(1L, 1L);

            // then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("방 없음 → CHAT_ROOM_NOT_FOUND 예외")
        void roomNotFound() {
            // given
            given(chatRoomRepository.findById(99L)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> chatRoomService.findHistories(1L, 99L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND);
        }

        @Test
        @DisplayName("소유자 불일치 → CHAT_ROOM_FORBIDDEN 예외")
        void forbidden() {
            // given: 방 소유자 userId=1, 요청자 userId=2
            given(chatRoomRepository.findById(1L)).willReturn(Optional.of(room));

            // when & then
            assertThatThrownBy(() -> chatRoomService.findHistories(2L, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_ROOM_FORBIDDEN);
        }
    }
}
