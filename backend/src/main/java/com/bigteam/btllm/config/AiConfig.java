package com.bigteam.btllm.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

	/**
	 * Spring AI ChatClient 빈 등록
	 * ChatClient는 OllamaChatModel을 추상화하여 스트리밍·동기 호출을 통일된 인터페이스로 제공
	 */
	@Bean
	public ChatClient chatClient(OllamaChatModel ollamaChatModel) {
		return ChatClient.builder(ollamaChatModel)
			// 시스템 프롬프트: 모든 대화에 기본으로 적용되는 AI 역할 지시
			.defaultSystem("당신은 유용한 AI 어시스턴트입니다. 한국어로 답변해주세요.")
			.build();
	}
}