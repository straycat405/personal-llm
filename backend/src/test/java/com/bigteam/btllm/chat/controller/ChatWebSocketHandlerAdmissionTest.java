package com.bigteam.btllm.chat.controller;

import com.bigteam.btllm.chat.repository.ChatHistoryRepository;
import com.bigteam.btllm.chat.repository.ChatRoomRepository;
import com.bigteam.btllm.chat.service.OllamaGenerationQueue;
import com.bigteam.btllm.chat.service.ThinkingRouter;
import com.bigteam.btllm.chat.tools.LlmTools;
import com.bigteam.btllm.common.jwt.JwtProvider;
import com.bigteam.btllm.config.ChatClientFactory;
import com.bigteam.btllm.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

/**
 * [범위] P1 #5 GPU admission control — ChatWebSocketHandler에 새로 생긴 in-flight
 * 취소/세대(generation) 관리 로직을 검증한다. ChatClient의 fluent 체인 전체를 목으로
 * 재현하는 대신, 실제 동시성 정확성이 걸린 package-private 헬퍼만 직접 호출한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatWebSocketHandler — GPU admission control")
class ChatWebSocketHandlerAdmissionTest {

    @Mock ChatClientFactory chatClientFactory;
    @Mock ChatRoomRepository chatRoomRepository;
    @Mock ChatHistoryRepository chatHistoryRepository;
    @Mock JwtProvider jwtProvider;
    @Mock UserRepository userRepository;
    @Mock ObjectMapper objectMapper;
    @Mock LlmTools llmTools;
    @Mock ThinkingRouter thinkingRouter;
    @Mock OllamaGenerationQueue ollamaGenerationQueue;
    @Mock WebSocketSession session;

    private ChatWebSocketHandler handler;
    private Map<String, Object> attributes;

    @BeforeEach
    void setUp() {
        handler = new ChatWebSocketHandler(chatClientFactory, chatRoomRepository, chatHistoryRepository,
            jwtProvider, userRepository, objectMapper, llmTools, thinkingRouter, ollamaGenerationQueue);
        // [설계] 실제 WebSocketSession#getAttributes()도 ConcurrentHashMap이라 동일 구현으로 맞춘다.
        attributes = new ConcurrentHashMap<>();
        // [설계] isCancellation 테스트는 session을 전혀 쓰지 않는다 — 공용 stub이 strict-stub
        // "unused" 오류를 내지 않도록 lenient로 표시한다.
        lenient().when(session.getAttributes()).thenReturn(attributes);
        lenient().when(session.getId()).thenReturn("test-session");
    }

    @Nested
    @DisplayName("세대(generation) 관리")
    class Generation {

        @Test
        @DisplayName("호출할 때마다 세대 번호가 증가한다")
        void generationIncreasesMonotonically() {
            assertThat(handler.beginNewGeneration(session)).isEqualTo(1L);
            assertThat(handler.beginNewGeneration(session)).isEqualTo(2L);
            assertThat(handler.beginNewGeneration(session)).isEqualTo(3L);
        }

        @Test
        @DisplayName("새 세대를 시작하면 이전 in-flight 취소 핸들이 실행된다")
        void beginningNewGenerationCancelsPrevious() {
            AtomicInteger cancelCount = new AtomicInteger(0);
            handler.beginNewGeneration(session);
            attributes.put("inFlightCancel", (Runnable) cancelCount::incrementAndGet);

            handler.beginNewGeneration(session); // 두 번째 요청 시작 → 첫 번째 취소돼야 함

            assertThat(cancelCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("최신 세대의 endInFlight는 취소 핸들을 정리한다")
        void endInFlightClearsHandleForCurrentGeneration() {
            long generation = handler.beginNewGeneration(session);
            attributes.put("inFlightCancel", (Runnable) () -> {});

            handler.endInFlight(session, generation);

            assertThat(attributes.get("inFlightCancel")).isNull();
        }

        @Test
        @DisplayName("낡은(구) 세대의 endInFlight는 최신 세대의 취소 핸들을 건드리지 않는다")
        void staleEndInFlightDoesNotClearNewerHandle() {
            long oldGeneration = handler.beginNewGeneration(session);
            long newGeneration = handler.beginNewGeneration(session); // 새 요청이 곧바로 들어옴
            Runnable newHandle = () -> {};
            attributes.put("inFlightCancel", newHandle);

            // 늦게 끝난 이전(oldGeneration) 작업의 정리 코드가 뒤늦게 실행되는 상황을 재현한다.
            handler.endInFlight(session, oldGeneration);

            assertThat(attributes.get("inFlightCancel")).isSameAs(newHandle);
            assertThat(newGeneration).isGreaterThan(oldGeneration);
        }
    }

    @Nested
    @DisplayName("cancelInFlight")
    class CancelInFlight {

        @Test
        @DisplayName("등록된 취소 핸들이 없으면 아무 일도 하지 않는다")
        void noOpWhenNothingRegistered() {
            handler.cancelInFlight(session); // 예외 없이 조용히 통과해야 한다
        }

        @Test
        @DisplayName("취소 핸들이 예외를 던져도 전파하지 않는다")
        void swallowsExceptionFromCancelHandle() {
            attributes.put("inFlightCancel", (Runnable) () -> {
                throw new IllegalStateException("boom");
            });

            handler.cancelInFlight(session); // 예외가 전파되면 안 된다(연결 종료 처리를 막으면 안 됨)

            assertThat(attributes.get("inFlightCancel")).isNull();
        }
    }

    @Nested
    @DisplayName("isCancellation")
    class IsCancellation {

        @Test
        @DisplayName("InterruptedException(직접/원인 체인)은 취소로 판정한다")
        void detectsInterruptedException() {
            assertThat(handler.isCancellation(new InterruptedException())).isTrue();
            assertThat(handler.isCancellation(new RuntimeException(new InterruptedException()))).isTrue();
        }

        @Test
        @DisplayName("CancellationException은 취소로 판정한다")
        void detectsCancellationException() {
            assertThat(handler.isCancellation(new CancellationException())).isTrue();
        }

        @Test
        @DisplayName("무관한 예외이고 스레드가 인터럽트 상태가 아니면 취소가 아니다")
        void unrelatedExceptionIsNotCancellation() {
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
            assertThat(handler.isCancellation(new RuntimeException("network timeout"))).isFalse();
        }
    }
}
