package com.bigteam.btllm.chat.controller;

import com.bigteam.btllm.chat.dto.ChatHistoryResponse;
import com.bigteam.btllm.chat.dto.ChatRoomResponse;
import com.bigteam.btllm.chat.entity.MessageRole;
import com.bigteam.btllm.chat.service.ChatRoomService;
import com.bigteam.btllm.common.exception.BusinessException;
import com.bigteam.btllm.common.exception.ErrorCode;
import com.bigteam.btllm.common.jwt.AuthUser;
import com.bigteam.btllm.common.jwt.JwtProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ChatRoomController.class)
@DisplayName("ChatRoomController MockMvc 테스트")
class ChatRoomControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean ChatRoomService chatRoomService;
    // [설계] SecurityConfig가 JwtProvider 생성자 주입 → @MockitoBean으로 의존성 충족
    @MockitoBean JwtProvider jwtProvider;

    // ── 픽스처 ───────────────────────────────────────────────────
    private static final Long USER_ID = 1L;
    private static final String USER_EMAIL = "test@test.com";

    private static final ChatRoomResponse ROOM_RESPONSE = new ChatRoomResponse(
        1L, "테스트 채팅방", "conv-uuid-1234",
        LocalDateTime.of(2026, 5, 23, 10, 0),
        LocalDateTime.of(2026, 5, 23, 10, 0)
    );

    // [설계] JWT 없이 AuthUser principal을 SecurityContext에 직접 주입
    // JwtAuthFilter 우회 → 인증 로직이 아닌 컨트롤러 동작에만 집중
    private org.springframework.test.web.servlet.request.RequestPostProcessor asUser() {
        AuthUser authUser = new AuthUser(USER_ID, USER_EMAIL);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
            authUser, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        return authentication(auth);
    }

    // ── POST /api/v1/chat-rooms ───────────────────────────────────
    @Nested
    @DisplayName("POST /api/v1/chat-rooms")
    class Create {

        @Test
        @DisplayName("201 Created — 채팅방 생성 성공")
        void success() throws Exception {
            // given
            given(chatRoomService.create(eq(USER_ID), any())).willReturn(ROOM_RESPONSE);

            // when & then
            mockMvc.perform(post("/api/v1/chat-rooms")
                    .with(asUser()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("title", "테스트 채팅방"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.title").value("테스트 채팅방"))
                .andExpect(jsonPath("$.data.conversationId").value("conv-uuid-1234"));
        }

        @Test
        @DisplayName("400 Bad Request — 빈 title 입력 시 Validation 실패")
        void blankTitle() throws Exception {
            mockMvc.perform(post("/api/v1/chat-rooms")
                    .with(asUser()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("title", ""))))
                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("401 Unauthorized — 인증 없이 요청")
        void unauthorized() throws Exception {
            mockMvc.perform(post("/api/v1/chat-rooms")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("title", "방"))))
                .andExpect(status().isUnauthorized());
        }
    }

    // ── GET /api/v1/chat-rooms ────────────────────────────────────
    @Nested
    @DisplayName("GET /api/v1/chat-rooms")
    class FindAll {

        @Test
        @DisplayName("200 OK — 채팅방 목록 반환")
        void success() throws Exception {
            // given
            ChatRoomResponse room2 = new ChatRoomResponse(
                2L, "두 번째 방", "conv-uuid-5678",
                LocalDateTime.of(2026, 5, 23, 11, 0),
                LocalDateTime.of(2026, 5, 23, 11, 0)
            );
            given(chatRoomService.findAllByUser(USER_ID)).willReturn(List.of(ROOM_RESPONSE, room2));

            // when & then
            mockMvc.perform(get("/api/v1/chat-rooms").with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].title").value("테스트 채팅방"))
                .andExpect(jsonPath("$.data[1].title").value("두 번째 방"));
        }

        @Test
        @DisplayName("200 OK — 채팅방 없을 때 빈 배열 반환")
        void emptyList() throws Exception {
            // given
            given(chatRoomService.findAllByUser(USER_ID)).willReturn(List.of());

            // when & then
            mockMvc.perform(get("/api/v1/chat-rooms").with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        }
    }

    // ── DELETE /api/v1/chat-rooms/{roomId} ───────────────────────
    @Nested
    @DisplayName("DELETE /api/v1/chat-rooms/{roomId}")
    class Delete {

        @Test
        @DisplayName("204 No Content — 정상 삭제")
        void success() throws Exception {
            // given
            willDoNothing().given(chatRoomService).delete(USER_ID, 1L);

            // when & then
            mockMvc.perform(delete("/api/v1/chat-rooms/1").with(asUser()).with(csrf()))
                .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("403 Forbidden — 소유자 불일치")
        void forbidden() throws Exception {
            // given: void 메서드 예외 stub → willThrow().given() 패턴
            willThrow(new BusinessException(ErrorCode.CHAT_ROOM_FORBIDDEN))
                .given(chatRoomService).delete(USER_ID, 99L);

            // when & then
            mockMvc.perform(delete("/api/v1/chat-rooms/99").with(asUser()).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CHAT_ROOM_FORBIDDEN"));
        }

        @Test
        @DisplayName("404 Not Found — 존재하지 않는 방")
        void notFound() throws Exception {
            // given
            willThrow(new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND))
                .given(chatRoomService).delete(USER_ID, 99L);

            // when & then
            mockMvc.perform(delete("/api/v1/chat-rooms/99").with(asUser()).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHAT_ROOM_NOT_FOUND"));
        }
    }

    // ── GET /api/v1/chat-rooms/{roomId}/histories ────────────────
    @Nested
    @DisplayName("GET /api/v1/chat-rooms/{roomId}/histories")
    class FindHistories {

        @Test
        @DisplayName("200 OK — 대화 이력 반환")
        void success() throws Exception {
            // given
            List<ChatHistoryResponse> histories = List.of(
                new ChatHistoryResponse(1L, MessageRole.USER, "안녕하세요",
                    LocalDateTime.of(2026, 5, 23, 10, 0)),
                new ChatHistoryResponse(2L, MessageRole.ASSISTANT, "안녕하세요! 무엇을 도와드릴까요?",
                    LocalDateTime.of(2026, 5, 23, 10, 1))
            );
            given(chatRoomService.findHistories(USER_ID, 1L)).willReturn(histories);

            // when & then
            mockMvc.perform(get("/api/v1/chat-rooms/1/histories").with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].role").value("USER"))
                .andExpect(jsonPath("$.data[0].content").value("안녕하세요"))
                .andExpect(jsonPath("$.data[1].role").value("ASSISTANT"));
        }

        @Test
        @DisplayName("403 Forbidden — 소유자 불일치")
        void forbidden() throws Exception {
            // given
            given(chatRoomService.findHistories(USER_ID, 99L))
                .willThrow(new BusinessException(ErrorCode.CHAT_ROOM_FORBIDDEN));

            // when & then
            mockMvc.perform(get("/api/v1/chat-rooms/99/histories").with(asUser()))
                .andExpect(status().isForbidden());
        }
    }
}
