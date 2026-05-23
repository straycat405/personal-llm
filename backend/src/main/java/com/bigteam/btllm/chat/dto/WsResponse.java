package com.bigteam.btllm.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

/**
 * [역할] 서버 → 클라이언트 WebSocket 메시지 구조
 *
 * [설계 결정사항]
 * - type 필드로 메시지 종류 구분: 클라이언트가 단일 파서로 처리 가능
 *   TOKEN: LLM 스트리밍 토큰 조각
 *   DONE: 스트리밍 완료 + 토큰 사용량
 *   ERROR: 오류 발생
 * - @JsonInclude(NON_NULL): null 필드 미전송으로 페이로드 최소화
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WsResponse {

	public enum Type { TOKEN, DONE, ERROR }

	private final Type type;
	private final String content;      // TOKEN 타입
	private final String message;      // ERROR 타입
	private final Integer promptTokens;
	private final Integer completionTokens;
	private final Integer totalTokens; // DONE 타입

	public static WsResponse token(String content) {
		return WsResponse.builder().type(Type.TOKEN).content(content).build();
	}

	public static WsResponse done(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
		return WsResponse.builder()
			.type(Type.DONE)
			.promptTokens(promptTokens)
			.completionTokens(completionTokens)
			.totalTokens(totalTokens)
			.build();
	}

	public static WsResponse error(String message) {
		return WsResponse.builder().type(Type.ERROR).message(message).build();
	}
}