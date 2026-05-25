package com.bigteam.btllm.config;

import com.bigteam.btllm.chat.advisor.SafeQuestionAnswerAdvisor;
import com.bigteam.btllm.chat.advisor.TokenTrackingAdvisor;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [역할] LLM provider별 ChatClient 생성·캐시 팩토리
 *
 * [설계 결정사항]
 * - provider:model 키로 ConcurrentHashMap 캐시: Advisor 체인 불필요한 재생성 방지
 * - ObjectProvider<AnthropicChatModel>: SPRING_AI_ANTHROPIC_API_KEY 미설정 시 bean 없음
 *   → getIfAvailable() = null → Claude 비활성화 (앱 기동 실패 없음)
 * - Advisor 체인 5단계: 모든 provider 공통 적용 (provider 추가 시 자동 보장)
 * - defaultOptions()로 모델명·온도 bake-in: 캐시 키(provider:model)로 자동 라우팅
 */
@Slf4j
@Component
public class ChatClientFactory {

    // [설계] 모든 provider에 동일 적용 — 한국어 강제 + 비한국어 번역 지시
    private static final String SYSTEM_PROMPT = """
        You are a helpful AI assistant. You MUST always respond in Korean (한국어).
        NEVER use Chinese, English, or any other language in your response.
        When tool results contain non-Korean text, translate and summarize them in Korean.
        모든 답변은 반드시 한국어로만 작성하세요.
        """;

    private final OllamaChatModel ollamaChatModel;           // 로컬 Ollama — 항상 사용 가능
    private final AnthropicChatModel anthropicChatModel;     // SPRING_AI_ANTHROPIC_API_KEY 없으면 null
    private final GoogleGenAiChatModel geminiChatModel;      // GOOGLE_AI_API_KEY 없으면 null
    private final ChatMemory chatMemory;                     // JdbcChatMemory — 대화 이력 영속화
    private final VectorStore vectorStore;                   // pgVector — RAG 문서 검색
    private final TokenTrackingAdvisor tokenTrackingAdvisor; // 커스텀 — 토큰 사용량 추적·저장

    // [설계] provider:model 조합별 ChatClient 캐시 — ConcurrentHashMap: 스레드 안전 보장
    private final Map<String, ChatClient> cache = new ConcurrentHashMap<>();

    public ChatClientFactory(
        OllamaChatModel ollamaChatModel,
        ObjectProvider<AnthropicChatModel> anthropicChatModelProvider,   // bean 없어도 안전
        ObjectProvider<GoogleGenAiChatModel> geminiChatModelProvider,    // bean 없어도 안전
        ChatMemory chatMemory,
        VectorStore vectorStore,
        TokenTrackingAdvisor tokenTrackingAdvisor
    ) {
        this.ollamaChatModel = ollamaChatModel;

        // [설계] API key 미설정 시 AnthropicChatModel 빈 생성 자체가 실패 (simpleApiKey cannot be null)
        //        ObjectProvider.getIfAvailable()이 생성 실패 예외를 전파하므로 try-catch로 흡수
        //        → Claude 비활성화 상태로 앱 정상 기동
        AnthropicChatModel resolvedAnthropic;
        try {
            resolvedAnthropic = anthropicChatModelProvider.getIfAvailable();
        } catch (Exception e) {
            log.info("AnthropicChatModel 초기화 건너뜀 (SPRING_AI_ANTHROPIC_API_KEY 미설정): {}", e.getMessage());
            resolvedAnthropic = null;
        }
        this.anthropicChatModel = resolvedAnthropic;

        // [설계] GOOGLE_AI_API_KEY 미설정 시 GoogleGenAiChatModel 빈 생성 실패
        //        동일 패턴으로 try-catch 흡수 → Gemini 비활성화 상태로 앱 정상 기동
        GoogleGenAiChatModel resolvedGemini;
        try {
            resolvedGemini = geminiChatModelProvider.getIfAvailable();
        } catch (Exception e) {
            log.info("GoogleGenAiChatModel 초기화 건너뜀 (GOOGLE_AI_API_KEY 미설정): {}", e.getMessage());
            resolvedGemini = null;
        }
        this.geminiChatModel = resolvedGemini;

        this.chatMemory = chatMemory;
        this.vectorStore = vectorStore;
        this.tokenTrackingAdvisor = tokenTrackingAdvisor;
    }

    /**
     * provider + model 조합의 ChatClient 반환 (캐시 적중 시 재사용)
     *
     * @param provider "ollama" | "claude"
     * @param model    모델명 (예: "qwen3:8b", "claude-sonnet-4-6")
     * @throws IllegalStateException provider 사용 불가 시 (예: API key 미설정)
     */
    public ChatClient get(String provider, String model) {
        // computeIfAbsent: 동일 조합 최초 요청 시에만 build() 실행 (이후 캐시 반환)
        return cache.computeIfAbsent(provider + ":" + model, k -> build(provider, model));
    }

    /** 특정 provider 사용 가능 여부 (프론트엔드 /api/v1/models 응답에 사용) */
    public boolean isAvailable(String provider) {
        return switch (provider) {
            case "claude"  -> anthropicChatModel != null; // SPRING_AI_ANTHROPIC_API_KEY 설정 여부
            case "gemini"  -> geminiChatModel != null;    // GOOGLE_AI_API_KEY 설정 여부
            case "ollama"  -> true;                       // 로컬 서버 — 항상 사용 가능으로 간주
            default -> false;
        };
    }

    // ── private helpers ───────────────────────────────────────────

    private ChatClient build(String provider, String model) {
        ChatModel chatModel = resolveModel(provider); // provider → ChatModel 구현체

        ChatClient.Builder builder = ChatClient.builder(chatModel)
            .defaultSystem(SYSTEM_PROMPT) // 한국어 전용 시스템 프롬프트
            .defaultAdvisors(

                // ① 부적절 입력 차단 — LLM 호출 전 최우선 실행
                SafeGuardAdvisor.builder()
                    .sensitiveWords(List.of("씨발", "개새끼", "주민등록번호", "신용카드번호"))
                    .failureResponse("부적절한 입력이 감지되어 처리할 수 없습니다.")
                    .order(Ordered.HIGHEST_PRECEDENCE)
                    .build(),

                // ② pgVector 검색 → 관련 청크 프롬프트 주입 (RAG)
                //    topK=5: 최대 5개 청크, similarityThreshold=0.5: 저관련 청크 필터
                new SafeQuestionAnswerAdvisor(
                    vectorStore,
                    SearchRequest.builder().topK(5).similarityThreshold(0.5).build(),
                    Ordered.HIGHEST_PRECEDENCE + 1
                ),

                // ③ 대화 이력 주입 — conversationId는 요청 시 파라미터로 전달
                MessageChatMemoryAdvisor.builder(chatMemory).build(),

                // ④ 토큰 사용량 추적·DB 저장 (커스텀 StreamAdvisor)
                tokenTrackingAdvisor,

                // ⑤ 요청·응답 디버그 로거 (운영 환경 시 제거 예정)
                new SimpleLoggerAdvisor(Ordered.LOWEST_PRECEDENCE - 1)
            );

        // [설계] provider별 옵션 분기: 모델명·온도를 defaultOptions로 bake-in
        //        캐시 키(provider:model)로 자동 라우팅, 요청별 오버라이드 불필요
        if ("claude".equals(provider)) {
            builder.defaultOptions(
                AnthropicChatOptions.builder()
                    .model(model)       // 예: claude-opus-4-7, claude-sonnet-4-6
                    .temperature(0.3)   // 낮은 온도 → 일관된 한국어 응답
                    .build()
            );
        } else if ("gemini".equals(provider)) {
            builder.defaultOptions(
                GoogleGenAiChatOptions.builder()
                    .model(model)       // 예: gemini-2.0-flash, gemini-2.5-flash
                    .temperature(0.3)
                    .build()
            );
        } else {
            // ollama 및 미지 provider → OllamaOptions 적용
            builder.defaultOptions(
                OllamaChatOptions.builder()
                    .model(model)       // 예: qwen3:8b, llama3:8b
                    .temperature(0.3)
                    .build()
            );
        }

        return builder.build();
    }

    /** provider 이름 → ChatModel 구현체 매핑 */
    private ChatModel resolveModel(String provider) {
        return switch (provider) {
            case "claude" -> {
                if (anthropicChatModel == null) {
                    throw new IllegalStateException(
                        "Claude를 사용하려면 SPRING_AI_ANTHROPIC_API_KEY 환경변수를 설정하세요."
                    );
                }
                yield anthropicChatModel;
            }
            case "gemini" -> {
                if (geminiChatModel == null) {
                    throw new IllegalStateException(
                        "Gemini를 사용하려면 GOOGLE_AI_API_KEY 환경변수를 설정하세요."
                    );
                }
                yield geminiChatModel;
            }
            default -> ollamaChatModel; // "ollama" 및 알 수 없는 provider → Ollama fallback
        };
    }
}
