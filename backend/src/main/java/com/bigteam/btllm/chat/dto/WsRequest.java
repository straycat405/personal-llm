package com.bigteam.btllm.chat.dto;

/**
 * [역할] 클라이언트 → 서버 WebSocket 메시지 구조
 *
 * [설계 결정사항]
 * - conversationId 포함: Spring AI MessageChatMemoryAdvisor가 대화 맥락을
 *   conversationId 단위로 관리하므로 메시지마다 함께 전송
 * - JWT 별도 전송 안 함: 연결 시 URL 쿼리 파라미터로 인증 처리
 */
public record WsRequest(String conversationId, String content) {
}