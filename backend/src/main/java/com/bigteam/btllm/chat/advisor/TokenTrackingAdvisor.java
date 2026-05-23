package com.bigteam.btllm.chat.advisor;

import com.bigteam.btllm.chat.entity.ChatHistory;
import com.bigteam.btllm.chat.entity.MessageRole;
import com.bigteam.btllm.chat.repository.ChatHistoryRepository;
import com.bigteam.btllm.chat.repository.ChatRoomRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * [역할] LLM 스트리밍 응답의 토큰 사용량을 추적하고 ChatHistory에 영속화하는 커스텀 Advisor
 *
 * [설계 결정사항]
 * - Advisor 패턴 선택: AOP 방식으로 관심사 분리
 *   WebSocketHandler에서 토큰 저장 로직을 직접 구현하면 핸들러가 DB 레이어에 의존하게 됨
 *   Advisor로 분리 → 핸들러는 스트리밍에만 집중
 * - StreamAdvisor 구현 (Spring AI 1.1.6): 구버전 StreamAroundAdvisor → StreamAdvisor 로 API 변경됨
 *   메서드명: aroundStream() → adviseStream(), 체인 호출: nextAroundStream() → nextStream()
 * - AdvisedRequest/Response 제거: 신버전에서 ChatClientRequest/ChatClientResponse 로 대체
 * - AtomicReference/AtomicLong: Flux는 여러 스레드에서 신호가 발생할 수 있으므로 Thread-safe 필요
 * - spring_ai_chat_memory 테이블(Spring AI 내부)과 chat_histories 테이블(커스텀) 분리:
 *   전자는 MessageChatMemoryAdvisor가 관리, 후자는 이 Advisor가 토큰 추적용으로 관리
 *
 * [주의] doOnComplete는 Reactor 스케줄러 스레드에서 실행됨 → @Transactional 적용 불가
 * JPA save는 별도 트랜잭션으로 처리 (SimpleJpaRepository.save 내부 @Transactional)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenTrackingAdvisor implements StreamAdvisor {

	private final ChatRoomRepository chatRoomRepository;
	private final ChatHistoryRepository chatHistoryRepository;

	@Override
	public String getName() {
		return "TokenTrackingAdvisor";
	}

	@Override
	public int getOrder() {
		// [설계] Memory(기본값 0) 이후, SimpleLogger 이전 위치
		return Ordered.LOWEST_PRECEDENCE - 2;
	}

	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
		StreamAdvisorChain chain) {
		AtomicLong inputTokens = new AtomicLong(0);
		AtomicLong outputTokens = new AtomicLong(0);
		AtomicLong totalTokens = new AtomicLong(0);
		AtomicReference<String> fullContent = new AtomicReference<>("");

		return chain.nextStream(chatClientRequest)
			.doOnNext(response -> {
				if (response.chatResponse() == null) return;

				// [설계] 마지막 청크에만 usage 포함 (Ollama 기준) → 계속 업데이트하면 최종값 유지
				Usage usage = response.chatResponse().getMetadata().getUsage();
				if (usage != null) {
					if (usage.getPromptTokens() != null) inputTokens.set(usage.getPromptTokens());
					if (usage.getCompletionTokens() != null) outputTokens.set(usage.getCompletionTokens());
					if (usage.getTotalTokens() != null) totalTokens.set(usage.getTotalTokens());
				}

				// 응답 텍스트 청크 누적
				if (!response.chatResponse().getResults().isEmpty()) {
					String chunk = response.chatResponse().getResult().getOutput().getText();
					if (chunk != null) {
						fullContent.updateAndGet(existing -> existing + chunk);
					}
				}
			})
			.doOnComplete(() -> {
				// [설계] conversationId를 ChatClientRequest.advisorContext()에서 추출
				// ChatMemory.CONVERSATION_ID: Spring AI 1.1.6에서 변경된 공식 상수
				String conversationId = (String) chatClientRequest.context()
					.get(ChatMemory.CONVERSATION_ID);
				if (conversationId == null) return;

				chatRoomRepository.findByConversationId(conversationId)
					.ifPresent(room -> {
						ChatHistory history = ChatHistory.builder()
							.chatRoom(room)
							.role(MessageRole.ASSISTANT)
							.content(fullContent.get())
							.promptTokens((int) inputTokens.get())
							.completionTokens((int) outputTokens.get())
							.totalTokens((int) totalTokens.get())
							.build();
						chatHistoryRepository.save(history);
						log.debug("토큰 추적 저장 — conversationId: {}, total: {}",
							conversationId, totalTokens.get());
					});
			})
			.doOnError(e -> log.error("TokenTrackingAdvisor 오류: {}", e.getMessage()));
	}
}