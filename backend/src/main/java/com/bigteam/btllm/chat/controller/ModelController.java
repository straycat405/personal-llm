package com.bigteam.btllm.chat.controller;

import com.bigteam.btllm.common.response.ApiResponse;
import com.bigteam.btllm.config.ChatClientFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * [역할] 사용 가능한 LLM provider·모델 목록 제공
 *
 * [설계 결정사항]
 * - 정적 목록 반환: Ollama API 동적 질의 대신 하드코딩
 *   이유: 현재 지원 모델 한정적, 포트폴리오 단순화 우선
 * - available 필드: ChatClientFactory.isAvailable()로 API key 설정 여부 반영
 *   → 프론트엔드에서 비활성화 처리 (optgroup disabled)
 */
@RestController
@RequestMapping("/api/v1/models")
@RequiredArgsConstructor
public class ModelController {

    private final ChatClientFactory chatClientFactory; // provider 가용 여부 확인용

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> getModels() {
        List<Map<String, Object>> providers = List.of(

            // Ollama 로컬 provider — 항상 available
            Map.of(
                "provider", "ollama",
                "providerName", "Ollama (로컬)",
                "available", chatClientFactory.isAvailable("ollama"),
                "models", List.of(
                    Map.of("id", "qwen3:8b", "name", "Qwen3 8B", "description", "기본 로컬 모델")
                )
            ),

            // Anthropic Claude provider — SPRING_AI_ANTHROPIC_API_KEY 설정 시 available
            // [갱신 2026-08-27] Claude 5 세대로 교체 (Opus 4.7→5, Sonnet 4.6→5). Haiku 4.5는 그대로 최신.
            Map.of(
                "provider", "claude",
                "providerName", "Claude (Anthropic)",
                "available", chatClientFactory.isAvailable("claude"),
                "models", List.of(
                    Map.of("id", "claude-opus-5",             "name", "Claude Opus 5",   "description", "최고 성능"),
                    Map.of("id", "claude-sonnet-5",           "name", "Claude Sonnet 5", "description", "성능·속도 균형"),
                    Map.of("id", "claude-haiku-4-5-20251001", "name", "Claude Haiku 4.5", "description", "빠른 응답")
                )
            ),

            // Google Gemini provider — GOOGLE_AI_API_KEY 설정 시 available
            // [갱신 2026-08-27] Gemini 3 세대로 교체(공식 문서 ai.google.dev/gemini-api/docs/models 확인)
            Map.of(
                "provider", "gemini",
                "providerName", "Gemini (Google)",
                "available", chatClientFactory.isAvailable("gemini"),
                "models", List.of(
                    Map.of("id", "gemini-3.1-pro-preview", "name", "Gemini 3.1 Pro", "description", "최고 성능"),
                    Map.of("id", "gemini-3.7-flash", "name", "Gemini 3.7 Flash", "description", "최신 고성능"),
                    Map.of("id", "gemini-3.5-flash-lite", "name", "Gemini 3.5 Flash-Lite", "description", "경량·고속")
                )
            ),

            // OpenAI provider — SPRING_AI_OPENAI_API_KEY 설정 시 available
            // (로컬 qwen3:8b 대비 응답 품질·지연 비교 실험용)
            // [갱신 2026-08-27] GPT-5.6 세대로 교체(공식 문서 developers.openai.com/api/docs/models 확인) —
            //   4o 세대는 이 시점 기준 구형이라 목록에서 제외
            Map.of(
                "provider", "openai",
                "providerName", "ChatGPT (OpenAI)",
                "available", chatClientFactory.isAvailable("openai"),
                "models", List.of(
                    Map.of("id", "gpt-5.6-sol", "name", "GPT-5.6 Sol", "description", "최고 성능"),
                    Map.of("id", "gpt-5.6-terra", "name", "GPT-5.6 Terra", "description", "성능·속도 균형"),
                    Map.of("id", "gpt-5.6-luna", "name", "GPT-5.6 Luna", "description", "경량·고속")
                )
            )
        );

        return ApiResponse.ok(providers);
    }
}
