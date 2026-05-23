package com.bigteam.btllm.config;

import com.bigteam.btllm.chat.advisor.SafeQuestionAnswerAdvisor;
import com.bigteam.btllm.chat.advisor.TokenTrackingAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.util.List;

/**
 * [역할] Spring AI ChatClient 빈 등록 및 Advisor 체인 구성
 *
 * [설계 결정사항]
 * - Advisor 체인 순서 (낮은 order 값 = 먼저 실행):
 *   ① SafeGuardAdvisor    (HIGHEST_PRECEDENCE): 부적절 입력 LLM 호출 전 차단
 *   ② QuestionAnswerAdvisor (HIGHEST_PRECEDENCE+1): pgVector 검색 → 관련 청크 프롬프트 주입
 *      - Memory보다 먼저: 외부 문서 컨텍스트를 확보한 뒤 대화 이력과 함께 LLM에 전달
 *   ③ MessageChatMemory   (기본값 0): 대화 맥락 주입
 *   ④ TokenTrackingAdvisor (LOWEST-2): 응답 후 토큰 저장
 *   ⑤ SimpleLoggerAdvisor  (LOWEST-1): 디버그 로깅
 *
 * - QuestionAnswerAdvisor SearchRequest:
 *   topK=5: 최대 5개 청크 검색 (과다 컨텍스트로 인한 품질 저하 방지)
 *   similarityThreshold=0.5: 관련성 낮은 청크 필터링 (0.0이면 모든 청크 반환)
 *
 * [TODO] Phase 3 완료 후: similarityThreshold 튜닝 (실제 질의 결과 기반)
 */
@Configuration
public class AiConfig {

	@Bean
	public ChatClient chatClient(OllamaChatModel ollamaChatModel,
		ChatMemory chatMemory,
		VectorStore vectorStore,
		TokenTrackingAdvisor tokenTrackingAdvisor) {
		return ChatClient.builder(ollamaChatModel)
			.defaultSystem("""
				You are a helpful AI assistant. You MUST always respond in Korean (한국어).
				NEVER use Chinese, English, or any other language in your response.
				When tool results contain non-Korean text, translate and summarize them in Korean.
				모든 답변은 반드시 한국어로만 작성하세요.
				""")
			.defaultAdvisors(

				// ① 부적절 입력 필터 — LLM 호출 전 가장 먼저 실행
				SafeGuardAdvisor.builder()
					.sensitiveWords(List.of("씨발", "개새끼", "주민등록번호", "신용카드번호"))
					.failureResponse("부적절한 입력이 감지되어 처리할 수 없습니다.")
					.order(Ordered.HIGHEST_PRECEDENCE)
					.build(),

				// ② pgVector 검색 후 관련 청크를 프롬프트에 주입 — SafeGuard 통과 후 즉시
				new SafeQuestionAnswerAdvisor(
					vectorStore,
					SearchRequest.builder().topK(5).similarityThreshold(0.5).build(),
					Ordered.HIGHEST_PRECEDENCE + 1
				),

				// ③ 대화 맥락 자동 주입 — conversationId는 요청 시 파라미터로 전달
				MessageChatMemoryAdvisor.builder(chatMemory)
					.build(),

				// ④ 토큰 사용량 추적·저장 (커스텀)
				tokenTrackingAdvisor,

				// ⑤ 개발 디버깅 로거 — 운영 시 제거
				new SimpleLoggerAdvisor(Ordered.LOWEST_PRECEDENCE - 1)
			)
			.build();
	}
}