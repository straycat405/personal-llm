package com.bigteam.btllm.config;

import com.bigteam.btllm.chat.advisor.TokenTrackingAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;

import java.util.List;

/**
 * [역할] Spring AI ChatClient 빈 등록 및 Advisor 체인 구성
 *
 * [설계 결정사항]
 * - Advisor 체인 순서 (높은 우선순위 → 낮은 우선순위):
 *   ① SafeGuardAdvisor    (HIGHEST): 부적절 입력 LLM 호출 전 차단
 *   ② MessageChatMemory   (기본값 0): 대화 맥락 주입 — 반드시 SafeGuard 이후
 *   ③ TokenTrackingAdvisor (LOW-2): 응답 후 토큰 저장
 *   ④ SimpleLoggerAdvisor  (LOWEST-1): 디버그 로깅
 *
 * - ChatMemory 빈 주입: JdbcChatMemoryRepository 기반으로 Spring AI가 자동 구성
 *   → application.yaml의 spring.ai.chat.memory.repository.jdbc.initialize-schema: always 설정 필요
 *
 * [TODO] Phase 3: RetrievalAugmentationAdvisor 추가 (Memory 이후, TokenTracking 이전)
 */
@Configuration
public class AiConfig {

	@Bean
	public ChatClient chatClient(OllamaChatModel ollamaChatModel,
		ChatMemory chatMemory,
		TokenTrackingAdvisor tokenTrackingAdvisor) {
		return ChatClient.builder(ollamaChatModel)
			.defaultSystem("당신은 유용한 AI 어시스턴트입니다. 한국어로 답변해주세요.")
			.defaultAdvisors(
				// ① 부적절 입력 필터 — LLM 호출 전 가장 먼저 실행
				SafeGuardAdvisor.builder()
					.sensitiveWords(List.of("씨발", "개새끼", "주민등록번호", "신용카드번호"))
					.failureResponse("부적절한 입력이 감지되어 처리할 수 없습니다.")
					.build(),

				// ② 대화 맥락 자동 주입 — conversationId는 요청 시 파라미터로 전달
				MessageChatMemoryAdvisor.builder(chatMemory)
					.build(),

				// ③ 토큰 사용량 추적·저장 (커스텀)
				tokenTrackingAdvisor,

				// ④ 개발 디버깅 로거 — 운영 시 제거
				new SimpleLoggerAdvisor(Ordered.LOWEST_PRECEDENCE - 1)
			)
			.build();
	}
}