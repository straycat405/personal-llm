package com.bigteam.btllm.chat.controller;

import com.bigteam.btllm.chat.dto.WsRequest;
import com.bigteam.btllm.chat.dto.WsResponse;
import com.bigteam.btllm.chat.repository.ChatRoomRepository;
import com.bigteam.btllm.common.jwt.JwtProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;

/**
 * [역할] WebSocket 연결 관리 및 LLM 스트리밍 응답 처리
 *
 * [설계 결정사항]
 * - JWT를 URL 쿼리 파라미터로 수신: ws://host/ws/chat?token=<jwt>
 *   브라우저 WebSocket API는 커스텀 헤더를 지원하지 않음
 *   쿼리 파라미터 방식이 브라우저 환경에서 가장 현실적인 인증 방법
 * - Raw WebSocket 유지 (STOMP 미사용): 1:1 채팅에 메시지 브로커 불필요, 오버엔지니어링 방지
 * - subscribe() 비동기 방식: blockLast()로 서블릿 스레드 블로킹 대신
 *   Reactor 스케줄러에서 토큰을 비동기 전송 → 서버 스레드 효율 개선
 * - synchronized(session): WebSocketSession.sendMessage()는 Thread-safe 미보장
 *   Reactor 멀티스레드 환경에서 동시 sendMessage 방지
 *
 * [주의] conversationId 소유권 검증 필수:
 *   userId만 검증 시 다른 사용자 채팅방 conversationId로 타인 대화 이력 열람 가능
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final ChatClient chatClient;
    private final ChatRoomRepository chatRoomRepository;
    private final JwtProvider jwtProvider;
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // URL 쿼리 파라미터에서 JWT 추출 및 검증
        String token = extractToken(session);
        if (token == null || !jwtProvider.isValid(token)) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        Claims claims = jwtProvider.validateAndGetClaims(token);
        // [설계] userId를 세션 속성에 저장: 이후 메시지 처리 시 재검증 불필요
        session.getAttributes().put("userId", Long.valueOf(claims.getSubject()));
        send(session, objectMapper.writeValueAsString(
            WsResponse.builder().type(WsResponse.Type.TOKEN).content("").build()));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Long userId = (Long) session.getAttributes().get("userId");
        if (userId == null) {
            sendSafe(session, WsResponse.error("인증 정보 없음"));
            return;
        }

        WsRequest request;
        try {
            request = objectMapper.readValue(message.getPayload(), WsRequest.class);
        } catch (Exception e) {
            sendSafe(session, WsResponse.error("잘못된 메시지 형식"));
            return;
        }

        // conversationId가 요청한 사용자 소유인지 검증
        boolean isOwner = chatRoomRepository.findByConversationId(request.conversationId())
            .map(room -> room.getUser().getId().equals(userId))
            .orElse(false);
        if (!isOwner) {
            sendSafe(session, WsResponse.error("채팅방을 찾을 수 없습니다."));
            return;
        }

        // Spring AI 스트리밍 시작
        chatClient.prompt()
            .user(request.content())
            .advisors(spec -> spec.param(
                ChatMemory.CONVERSATION_ID,
                request.conversationId()))
            .stream()
            .content() // Flux<String> — TokenTrackingAdvisor가 토큰 저장 담당
            .subscribe(
                // 토큰 조각 클라이언트 전송
                token -> sendSafe(session, WsResponse.token(token)),
                // LLM 오류 처리
                error -> {
                    log.error("LLM 스트리밍 오류 — session: {}, error: {}",
                        session.getId(), error.getMessage());
                    sendSafe(session, WsResponse.error("AI 응답 중 오류가 발생했습니다."));
                },
                // 스트리밍 완료
                () -> sendSafe(session, WsResponse.done(null, null, null))
            );
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.debug("WebSocket 연결 종료 — session: {}, status: {}", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WebSocket 전송 오류 — session: {}", session.getId(), exception);
        session.close(CloseStatus.SERVER_ERROR);
    }

    // ── private helpers ──────────────────────────────────

    private String extractToken(WebSocketSession session) {
        String query = session.getUri() != null ? session.getUri().getQuery() : null;
        if (query == null) return null;
        for (String param : query.split("&")) {
            if (param.startsWith("token=")) return param.substring(6);
        }
        return null;
    }

    private void sendSafe(WebSocketSession session, WsResponse response) {
        try {
            send(session, objectMapper.writeValueAsString(response));
        } catch (Exception e) {
            log.warn("WebSocket 전송 실패 — session: {}", session.getId());
        }
    }

    private void send(WebSocketSession session, String json) throws IOException {
        // [주의] Reactor 멀티스레드 환경에서 동시 전송 방지
        synchronized (session) {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(json));
            }
        }
    }
}