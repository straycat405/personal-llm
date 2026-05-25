package com.bigteam.btllm.chat.controller;

import com.bigteam.btllm.chat.dto.WsRequest;
import com.bigteam.btllm.chat.dto.WsResponse;
import com.bigteam.btllm.chat.repository.ChatRoomRepository;
import com.bigteam.btllm.chat.tools.LlmTools;
import com.bigteam.btllm.common.jwt.JwtProvider;
import com.bigteam.btllm.config.ChatClientFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * [역할] WebSocket 연결 관리 및 LLM 스트리밍 응답 처리
 *
 * [설계 결정사항]
 * - provider·model을 URL 쿼리 파라미터로 수신: ?token=<jwt>&provider=claude&model=claude-sonnet-4-6
 *   WS 연결 시 모델 고정 → 스트리밍 중 model 전환 없음 (model 변경 = WS 재연결)
 * - ChatClientFactory: provider:model 조합별 ChatClient 캐시 (Advisor 체인 공유)
 * - isToolCallText 필터: Ollama(qwen2.5/qwen3) 전용
 *   Claude는 텍스트 형식 tool call 누출 없음 → provider 조건부 적용
 * - model 파라미터 URLDecoder: qwen3:8b처럼 ':'가 포함된 값 안전 파싱
 *
 * [주의] conversationId 소유권 검증 필수:
 *   userId만 검증 시 다른 사용자 conversationId로 타인 대화 이력 열람 가능
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {

    // [변경] ChatClient 고정 주입 → ChatClientFactory로 교체 (provider별 동적 라우팅)
    private final ChatClientFactory chatClientFactory;
    private final ChatRoomRepository chatRoomRepository;
    private final JwtProvider jwtProvider;
    private final ObjectMapper objectMapper;
    private final LlmTools llmTools; // Tool Calling 3종 (크롤러·이력검색·사용량조회)

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // JWT 추출 및 검증
        String token = extractParam(session, "token");
        if (token == null || !jwtProvider.isValid(token)) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        Claims claims = jwtProvider.validateAndGetClaims(token);
        // userId 세션 저장: 이후 메시지 처리 시 재검증 불필요
        session.getAttributes().put("userId", Long.valueOf(claims.getSubject()));

        // [신규] provider·model 파라미터 추출 → 세션에 저장
        // 기본값: ollama + qwen3:8b → URL 파라미터 없어도 기존 동작 유지 (하위 호환)
        String provider = extractParam(session, "provider");
        String model = extractParam(session, "model");
        if (provider == null || provider.isBlank()) provider = "ollama";
        if (model == null || model.isBlank()) model = "qwen3:8b";
        session.getAttributes().put("provider", provider);
        session.getAttributes().put("model", model);

        log.debug("WS 연결 — session: {}, provider: {}, model: {}", session.getId(), provider, model);
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

        // conversationId가 요청한 userId 소유인지 검증
        boolean isOwner = chatRoomRepository.findByConversationId(request.conversationId())
            .map(room -> room.getUser().getId().equals(userId))
            .orElse(false);
        if (!isOwner) {
            sendSafe(session, WsResponse.error("채팅방을 찾을 수 없습니다."));
            return;
        }

        // [신규] 세션에서 provider·model 꺼내 ChatClient 획득
        String provider = (String) session.getAttributes().getOrDefault("provider", "ollama");
        String model = (String) session.getAttributes().getOrDefault("model", "qwen3:8b");
        ChatClient chatClient;
        try {
            chatClient = chatClientFactory.get(provider, model);
        } catch (IllegalStateException e) {
            // API key 미설정 등 provider 사용 불가 → 사용자에게 오류 전달
            sendSafe(session, WsResponse.error(e.getMessage()));
            return;
        }

        // Spring AI 스트리밍 시작
        // [설계] .tools()로 Tool 등록, .toolContext()로 conversationId 주입
        //        ToolContext는 LLM 파라미터 스키마에 포함되지 않으므로 내부 식별자 노출 없음
        chatClient.prompt()
            .user(request.content())
            .advisors(spec -> spec.param(
                ChatMemory.CONVERSATION_ID, request.conversationId()))
            .tools(llmTools)
            .toolContext(Map.of("conversationId", request.conversationId()))
            .stream()
            .chatResponse()
            .subscribe(
                response -> {
                    if (response.getResult() == null) return;
                    var output = response.getResult().getOutput();
                    if (output == null) return;
                    // 구조적 tool call 청크 (toolCalls 필드)는 전송 불필요
                    if (!output.getToolCalls().isEmpty()) return;
                    String text = output.getText();
                    if (text == null || text.isBlank()) return;
                    // [변경] Ollama(qwen2.5/qwen3)만 텍스트 형식 tool call 누출 — provider 조건부 필터
                    if ("ollama".equals(provider) && isToolCallText(text)) return;
                    sendSafe(session, WsResponse.token(text));
                },
                error -> {
                    log.error("LLM 스트리밍 오류 — session: {}, provider: {}, error: {}",
                        session.getId(), provider, error.getMessage());
                    sendSafe(session, WsResponse.error("AI 응답 중 오류가 발생했습니다."));
                },
                () -> sendSafe(session, WsResponse.done(null, null, null))
            );
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.debug("WS 연결 종료 — session: {}, status: {}", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WS 전송 오류 — session: {}", session.getId(), exception);
        session.close(CloseStatus.SERVER_ERROR);
    }

    // ── private helpers ─────────────────────────────────────────

    // [설계] qwen2.5/qwen3이 텍스트 형식으로 도구 호출을 출력하는 2가지 패턴 필터
    //        Claude는 해당 없음 → "ollama" provider 조건부로만 호출
    private boolean isToolCallText(String text) {
        return text.contains("<tool_call>")
            || text.contains("</tool_call>")
            || text.contains("-tools.call(")
            || text.contains("tools.call(\"");
    }

    /**
     * URL 쿼리 파라미터에서 특정 키의 값 추출 + URL 디코딩
     * model 값은 ':'를 포함할 수 있어 encodeURIComponent 후 전달됨 (예: qwen3%3A8b → qwen3:8b)
     */
    private String extractParam(WebSocketSession session, String name) {
        String query = session.getUri() != null ? session.getUri().getQuery() : null;
        if (query == null) return null;
        for (String param : query.split("&")) {
            String[] parts = param.split("=", 2); // 값에 '=' 포함 시 분리 방지
            if (parts.length == 2 && parts[0].equals(name)) {
                return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private void sendSafe(WebSocketSession session, WsResponse response) {
        try {
            send(session, objectMapper.writeValueAsString(response));
        } catch (Exception e) {
            log.warn("WS 전송 실패 — session: {}", session.getId());
        }
    }

    private void send(WebSocketSession session, String json) throws IOException {
        // [주의] Reactor 멀티스레드 환경에서 동시 sendMessage 방지
        synchronized (session) {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(json));
            }
        }
    }
}
