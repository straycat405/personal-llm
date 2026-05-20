package com.bigteam.btllm.config;

import com.bigteam.btllm.chat.controller.ChatWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket // Spring WebSocket 기능 활성화
public class WebSocketConfig implements WebSocketConfigurer {

	// 실제 메시지를 처리할 핸들러 주입
	private final ChatWebSocketHandler chatWebSocketHandler;

	public WebSocketConfig(ChatWebSocketHandler chatWebSocketHandler) {
		this.chatWebSocketHandler = chatWebSocketHandler;
	}

	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		registry
			// /ws/chat 경로로 들어오는 WebSocket 연결을 chatWebSocketHandler에 위임
			.addHandler(chatWebSocketHandler, "/ws/chat")
			// 개발 단계: 모든 Origin 허용 (운영 시 프론트엔드 도메인으로 제한)
			.setAllowedOrigins("*");
	}
}