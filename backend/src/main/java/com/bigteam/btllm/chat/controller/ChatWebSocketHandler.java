package com.bigteam.btllm.chat.controller;

import com.bigteam.btllm.chat.service.OllamaGenerationQueue;
import com.bigteam.btllm.chat.service.ThinkingRouter;
import com.bigteam.btllm.chat.dto.WsRequest;
import com.bigteam.btllm.chat.dto.WsResponse;
import com.bigteam.btllm.chat.entity.ChatHistory;
import com.bigteam.btllm.chat.entity.MessageRole;
import com.bigteam.btllm.chat.repository.ChatHistoryRepository;
import com.bigteam.btllm.chat.repository.ChatRoomRepository;
import com.bigteam.btllm.chat.tools.LlmTools;
import com.bigteam.btllm.common.jwt.JwtProvider;
import com.bigteam.btllm.config.ChatClientFactory;
import com.bigteam.btllm.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import reactor.core.Disposable;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * [역할] WebSocket 연결 관리 및 LLM 스트리밍 응답 처리
 *
 * [설계 결정사항]
 * - provider·model을 URL 쿼리 파라미터로 수신: ?token=<jwt>&provider=claude&model=claude-sonnet-5
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
    private final ChatHistoryRepository chatHistoryRepository; // 사용자 메시지 영속화 (표시용 이력)
    private final JwtProvider jwtProvider;
    // [보안] REST(JwtAuthFilter)와 동일하게 JWT subject를 활성 사용자 DB 레코드와 대조한다.
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final LlmTools llmTools; // Tool Calling 3종 (크롤러·이력검색·사용량조회)
    private final ThinkingRouter thinkingRouter; // 질의 성격별 thinking 켬/끔 판정
    private final OllamaGenerationQueue ollamaGenerationQueue; // GPU 단일 슬롯 admission control

    // [설계] 세션당 in-flight 취소 핸들/세대 번호 저장 키. WebSocketSession#getAttributes()는
    //   ConcurrentHashMap이라 별도 동기화 없이 안전하게 get/put 가능하다(userId 등 기존 값과 동일 패턴).
    private static final String ATTR_IN_FLIGHT_CANCEL = "inFlightCancel";
    private static final String ATTR_IN_FLIGHT_GEN = "inFlightGen";

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // JWT 추출 및 검증
        String token = extractParam(session, "token");
        if (token == null || !jwtProvider.isValid(token)) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        Claims claims = jwtProvider.validateAndGetClaims(token);
        Long userId = Long.valueOf(claims.getSubject());
        if (!userRepository.existsById(userId)) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        // userId 세션 저장: 이후 메시지 처리 시 재검증 불필요
        session.getAttributes().put("userId", userId);

        // [신규] provider·model 파라미터 추출 → 세션에 저장
        // 기본값: ollama + qwen3:8b → URL 파라미터 없어도 기존 동작 유지 (하위 호환)
        String provider = extractParam(session, "provider");
        String model = extractParam(session, "model");
        if (provider == null || provider.isBlank()) provider = "ollama";
        if (model == null || model.isBlank()) model = "qwen3:8b";
        session.getAttributes().put("provider", provider);
        session.getAttributes().put("model", model);

        log.debug("WS 연결 — session: {}, provider: {}, model: {}", session.getId(), provider, model);
        // 빈 TOKEN은 프론트가 "첫 토큰 도착"으로 오인해 대기 UI를 조기에 숨긴다.
        // 연결 완료는 내용 스트림과 분리된 READY 이벤트로 전달한다.
        send(session, objectMapper.writeValueAsString(WsResponse.ready()));
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
        var roomOpt = chatRoomRepository.findByConversationId(request.conversationId())
            .filter(room -> room.getUser().getId().equals(userId));
        if (roomOpt.isEmpty()) {
            sendSafe(session, WsResponse.error("채팅방을 찾을 수 없습니다."));
            return;
        }

        // [설계] 사용자 메시지를 여기서 저장하는 이유:
        //   TokenTrackingAdvisor는 ASSISTANT 응답만 저장한다. 그래서 새로고침 후
        //   방을 다시 열면 내 질문은 사라지고 AI 답변만 남아 대화가 이어지지 않았다.
        //   Advisor 체인 안에서 유추하는 대신, 원본 요청 문자열을 그대로 가진
        //   이 지점에서 저장해 "무엇을 물었는지"가 정확히 남도록 한다.
        //   (LLM 자체의 문맥은 Spring AI의 별도 메모리 테이블이 담당 — 표시용과 분리)
        try {
            chatHistoryRepository.save(ChatHistory.builder()
                .chatRoom(roomOpt.get())
                .role(MessageRole.USER)
                .content(request.content())
                .build());
        } catch (Exception e) {
            // 이력 저장 실패가 대화 자체를 막지는 않도록 흡수 — 응답은 정상 진행
            log.warn("사용자 메시지 저장 실패 — conversationId: {}, error: {}",
                request.conversationId(), e.getMessage());
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

        // [보안/안정성] 같은 세션이 이전 요청을 아직 스트리밍 중인데 새 메시지를 보내면
        //   기존 요청을 취소하고 새로 시작한다 — "세션당 in-flight 1개"를 강제한다(P1 #5,
        //   HANDOFF 4-1: 취소 없이 계속 쌓이면 GPU/API 호출이 세션당 여러 건 동시 진행된다).
        //   프론트는 이미 스트리밍 중 입력을 막지만(ChatPage isStreaming), 멀티탭·API 오남용
        //   등 프론트 가드를 우회하는 경로에 대한 방어선이다.
        long generation = beginNewGeneration(session);

        // [설계] 질의 성격에 따라 thinking을 켜고 끈다. thinking은 지연과 품질을 정면으로
        //   맞바꾸므로(ON 88.9s/77.8% vs OFF 33.0s/57.1%), 복합 추론이 필요한 질의에만 켠다.
        //   Ollama 외 provider는 해당 옵션이 없으므로 그대로 둔다.
        var requestOptions = "ollama".equals(provider)
            ? chatClientFactory.ollamaOptions(model, thinkingRouter.shouldThink(request.content()))
            : null;

        // Spring AI 스트리밍 준비
        // [설계] .tools()로 Tool 등록, .toolContext()로 conversationId 주입
        //        ToolContext는 LLM 파라미터 스키마에 포함되지 않으므로 내부 식별자 노출 없음
        var prompt = chatClient.prompt();
        if (requestOptions != null) {
            prompt = prompt.options(requestOptions);
        }
        var finalPrompt = prompt
            .user(request.content())
            .advisors(spec -> spec.param(
                ChatMemory.CONVERSATION_ID, request.conversationId()))
            .tools(llmTools)
            // [보안] userId도 함께 주입 — searchKnowledgeBase가 호출자 소유 문서만 검색하도록
            //   ownerId를 여기서 흘려보낸다(LLM 파라미터 스키마에는 노출되지 않음).
            .toolContext(Map.of("conversationId", request.conversationId(), "userId", userId));

        if ("ollama".equals(provider)) {
            // [설계] GPU 물리 슬롯이 1개뿐이라 동시 실행을 강제로 직렬화하는 큐를 거친다.
            //   .blockLast()로 워커 스레드를 실제로 점유해야 동시성=1이 의미를 갖는다
            //   (.subscribe()로 넘기면 워커 스레드가 즉시 반환돼 직렬화가 무의미해진다).
            //   토큰은 doOnNext에서 곧바로 전송하므로 실시간 스트리밍 체감은 그대로 유지된다.
            Runnable task = () -> {
                try {
                    finalPrompt.stream().chatResponse()
                        .doOnNext(response -> forwardToken(session, provider, response))
                        .blockLast();
                    sendSafe(session, WsResponse.done(null, null, null));
                } catch (Exception e) {
                    if (isCancellation(e)) {
                        log.debug("GPU 큐 작업 취소됨 — session: {}", session.getId());
                    } else {
                        log.error("LLM 스트리밍 오류(큐) — session: {}, provider: {}, error: {}",
                            session.getId(), provider, e.getMessage());
                        sendSafe(session, WsResponse.error("AI 응답 중 오류가 발생했습니다."));
                    }
                } finally {
                    endInFlight(session, generation);
                }
            };

            try {
                var submission = ollamaGenerationQueue.submit(task);
                session.getAttributes().put(ATTR_IN_FLIGHT_CANCEL,
                    (Runnable) () -> submission.future().cancel(true));
                // 로컬 모델이 GPU 대기·문서 검색·콜드스타트로 첫 토큰까지 오래 걸려도
                // 사용자가 전송 실패나 멈춤으로 오해하지 않게 즉시 접수를 알린다.
                sendSafe(session, submission.queuedAhead() > 0
                    ? WsResponse.queued("다른 요청이 GPU를 사용 중입니다. 대기 중: 약 "
                        + submission.queuedAhead() + "건")
                    : WsResponse.queued("요청을 접수했습니다. 로컬 모델이 답변을 준비하고 있습니다."));
            } catch (RejectedExecutionException e) {
                endInFlight(session, generation);
                sendSafe(session, WsResponse.error(
                    "요청이 많아 대기열이 가득 찼습니다. 잠시 후 다시 시도해주세요."));
            }
        } else {
            // 상용 provider는 로컬 GPU를 쓰지 않으므로 큐를 거치지 않는다 — 물리적으로
            // 공유하는 자원이 없는데 로컬 슬롯 제약으로 직렬화하면 손해만 본다.
            sendSafe(session, WsResponse.queued("요청을 접수했습니다. 모델이 답변을 준비하고 있습니다."));
            Disposable disposable = finalPrompt.stream().chatResponse()
                .doFinally(signal -> endInFlight(session, generation))
                .subscribe(
                    response -> forwardToken(session, provider, response),
                    error -> {
                        log.error("LLM 스트리밍 오류 — session: {}, provider: {}, error: {}",
                            session.getId(), provider, error.getMessage());
                        sendSafe(session, WsResponse.error("AI 응답 중 오류가 발생했습니다."));
                    },
                    () -> sendSafe(session, WsResponse.done(null, null, null))
                );
            session.getAttributes().put(ATTR_IN_FLIGHT_CANCEL, (Runnable) disposable::dispose);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.debug("WS 연결 종료 — session: {}, status: {}", session.getId(), status);
        // [보안/안정성] 연결이 끊겨도 이미 시작된 생성이 백그라운드에서 GPU/API를 계속
        //   점유하던 문제(HANDOFF 4-1)를 막는다 — 대기 중이면 큐에서 제거, 실행 중이면
        //   워커 스레드를 인터럽트(Reactor blockLast가 이를 감지해 구독을 취소)한다.
        cancelInFlight(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WS 전송 오류 — session: {}", session.getId(), exception);
        session.close(CloseStatus.SERVER_ERROR);
    }

    // ── private helpers ─────────────────────────────────────────

    // ── in-flight 생성 취소 관리 ──────────────────────────────
    // [설계] 세대(generation) 번호를 두는 이유: 새 요청이 이전 요청을 취소하고 새 취소 핸들을
    //   세션에 등록한 "직후", 취소된 이전 작업의 finally/doFinally가 뒤늦게 실행되면서 방금
    //   등록한 새 핸들을 지워버리는 레이스가 생길 수 있다. 각 요청이 자신의 세대 번호를 들고
    //   있다가, 종료 시점에 "지금도 내가 최신 세대인지" 확인한 뒤에만 핸들을 지운다.

    // [설계] 아래 5개는 원래 private이지만 package-private로 완화했다 — 관리 대상 상태(생성
    //   세대, 취소 핸들)의 정확성이 이 admission-control 기능의 핵심이라 ChatClient 전체 fluent
    //   체인을 목(mock)으로 재현하지 않고도 동시성 로직 자체를 직접 검증할 수 있게 한다.

    void forwardToken(WebSocketSession session, String provider, ChatResponse response) {
        if (response.getResult() == null) return;
        var output = response.getResult().getOutput();
        if (output == null) return;
        // 구조적 tool call 청크 (toolCalls 필드)는 전송 불필요
        if (!output.getToolCalls().isEmpty()) return;
        String text = output.getText();
        // Markdown의 줄바꿈이 독립적인 공백 청크로 오는 경우가 있다.
        // isBlank()로 거르면 저장 이력은 정상인데 실시간 화면에서 제목·목록·표가
        // 한 줄로 붙는다. 내용이 완전히 없는 청크만 제외하고 개행은 그대로 전송한다.
        if (text == null || text.isEmpty()) return;
        // [변경] Ollama(qwen2.5/qwen3)만 텍스트 형식 tool call 누출 — provider 조건부 필터
        if ("ollama".equals(provider) && isToolCallText(text)) return;
        sendSafe(session, WsResponse.token(text));
    }

    /** 이전 in-flight를 취소하고 새 세대 번호를 발급한다. */
    long beginNewGeneration(WebSocketSession session) {
        cancelInFlight(session);
        var generation = (AtomicLong) session.getAttributes()
            .computeIfAbsent(ATTR_IN_FLIGHT_GEN, k -> new AtomicLong(0));
        return generation.incrementAndGet();
    }

    /** 현재 등록된 in-flight 취소 핸들이 있으면 실행하고 제거한다. */
    void cancelInFlight(WebSocketSession session) {
        Object prev = session.getAttributes().remove(ATTR_IN_FLIGHT_CANCEL);
        if (prev instanceof Runnable cancel) {
            try {
                cancel.run();
            } catch (Exception e) {
                log.warn("in-flight 취소 중 예외 — session: {}, error: {}", session.getId(), e.getMessage());
            }
        }
    }

    /** 작업 종료(완료/오류/취소) 시 호출 — 그 사이 더 새로운 세대가 시작됐다면 그 핸들을 건드리지 않는다. */
    void endInFlight(WebSocketSession session, long generation) {
        var current = (AtomicLong) session.getAttributes().get(ATTR_IN_FLIGHT_GEN);
        if (current != null && current.get() == generation) {
            session.getAttributes().remove(ATTR_IN_FLIGHT_CANCEL);
        }
    }

    /** Future#cancel(true)로 인터럽트된 blockLast()가 던지는 예외를 "정상 취소"로 구분한다. */
    boolean isCancellation(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof InterruptedException || cur instanceof CancellationException) {
                return true;
            }
            cur = cur.getCause();
        }
        return Thread.currentThread().isInterrupted();
    }

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
