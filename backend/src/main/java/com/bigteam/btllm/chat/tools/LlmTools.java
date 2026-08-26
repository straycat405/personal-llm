package com.bigteam.btllm.chat.tools;

import com.bigteam.btllm.chat.entity.ChatHistory;
import com.bigteam.btllm.chat.entity.MessageRole;
import com.bigteam.btllm.chat.repository.ChatHistoryRepository;
import com.bigteam.btllm.chat.repository.ChatRoomRepository;
import com.bigteam.btllm.rag.dto.EtlSourceResponse;
import com.bigteam.btllm.rag.service.EtlSourceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * [역할] LLM이 호출 가능한 Tool 4종 정의
 *
 * [설계 결정사항]
 * - ToolContext 활용: conversationId는 LLM이 아닌 ChatWebSocketHandler가 주입
 *   LLM에게 내부 식별자를 노출하지 않아도 되므로 보안·프롬프트 품질 향상
 * - Tool 메서드 반환값은 String: LLM이 이해할 수 있는 자연어 형식으로 반환
 * - crawlWebPage 3000자 제한: 과다 컨텍스트 주입 시 LLM 응답 품질 저하 방지
 * - searchKnowledgeBase: 기존 SafeQuestionAnswerAdvisor(상시 RAG 검색)를 대체 —
 *   모든 메시지마다 무조건 벡터 검색하던 것을, 모델이 필요하다고 판단할 때만 호출하는
 *   Tool로 전환 (성능·정확도 개선안 #4). 잡담에 불필요한 임베딩 호출·컨텍스트 오염 제거
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmTools {

	private final ChatRoomRepository chatRoomRepository;
	private final ChatHistoryRepository chatHistoryRepository;
	private final VectorStore vectorStore;
	private final EtlSourceService etlSourceService;  // 검색 0건 시 인덱싱된 문서 목록 안내용

	// [Tool 1] 웹 크롤러 — 사용자가 URL을 언급하거나 최신 정보를 요청할 때 LLM이 자동 호출
	@Tool(name = "crawlWebPage",
		description = "주어진 URL의 웹 페이지를 크롤링하여 텍스트 내용을 반환합니다. " +
			"사용자가 특정 URL의 내용을 요청하거나 최신 웹 정보가 필요할 때 사용하세요.")
	public String crawlWebPage(
		@ToolParam(description = "크롤링할 웹 페이지의 전체 URL (예: https://example.com)") String url
	) {
		try {
			// User-Agent 설정: 미설정 시 Wikipedia 등에서 HTTP 403 반환
			String text = Jsoup.connect(url)
				.userAgent("Mozilla/5.0 (compatible; BTLLM/1.0)")
				.timeout(10_000)
				.get()
				.body()
				.text();

			// 과다 컨텍스트 방지: 3000자 초과 시 잘라내고 말줄임 표시
			if (text.length() > 3000) {
				return text.substring(0, 3000) + "\n...(이하 생략)";
			}
			return text;
		} catch (Exception e) {
			log.warn("웹 크롤링 실패 — url: {}, error: {}", url, e.getMessage());
			return "페이지를 가져오는 데 실패했습니다: " + e.getMessage();
		}
	}

	// [Tool 2] 히스토리 검색 — 사용자가 "이전에 뭐라고 했지?" 같은 회고 질문 시 LLM이 호출
	@Tool(name = "searchChatHistory",
		description = "현재 대화방의 이전 대화 내용에서 특정 키워드를 검색합니다. " +
			"사용자가 과거 대화 내용을 찾거나 이전에 언급한 내용을 확인할 때 사용하세요.")
	public String searchChatHistory(
		@ToolParam(description = "검색할 키워드") String keyword,
		ToolContext toolContext   // [설계] LLM 파라미터 목록에 포함되지 않음 — Spring AI가 자동 주입
	) {
		// ChatWebSocketHandler에서 주입한 conversationId 추출
		String conversationId = (String) toolContext.getContext().get("conversationId");
		if (conversationId == null) {
			return "대화방 정보를 찾을 수 없습니다.";
		}

		// conversationId → chatRoomId 조회
		return chatRoomRepository.findByConversationId(conversationId)
			.map(room -> {
				// 키워드 포함 메시지 검색 (대소문자 무시)
				List<ChatHistory> results = chatHistoryRepository
					.findByChatRoomIdAndKeyword(room.getId(), keyword);

				if (results.isEmpty()) {
					return "'" + keyword + "'에 대한 이전 대화 내용을 찾을 수 없습니다.";
				}

				// 최대 5개, 발신자+내용 형식으로 반환
				return results.stream()
					.limit(5)
					.map(h -> "[" + h.getRole() + "] " + h.getContent())
					.collect(Collectors.joining("\n---\n"));
			})
			.orElse("대화방을 찾을 수 없습니다.");
	}

	// [Tool 3] 지식베이스 검색 — 사용자가 인덱싱된 문서 내용을 물을 때 LLM이 호출
	// [설계] topK=5, similarityThreshold=0.5 — 기존 SafeQuestionAnswerAdvisor와 동일 파라미터 유지
	//
	// [주의] description 문구가 호출률을 좌우한다.
	//   초기 문구는 "…필요할 때만 사용하세요"처럼 억제형이었는데, qwen3:8b가 문서 관련 질문
	//   ("방금 준 문서 뭐야?")에도 도구를 전혀 호출하지 않아 RAG가 사실상 동작하지 않았다.
	//   억제 문구를 빼고 호출 조건을 단정형으로 명시하니 정상 호출됐다.
	//   (작은 로컬 모델일수록 description의 어조에 민감하다 — 성능·UX 개선안 #4 트러블슈팅 참고)
	@Tool(name = "searchKnowledgeBase",
		description = "업로드된 문서(지식베이스)를 검색합니다. " +
			"사용자가 문서, 자료, 파일, PDF, 업로드한 내용, 인덱싱한 내용에 대해 물으면 반드시 이 도구를 호출하세요.")
	public String searchKnowledgeBase(
		@ToolParam(description = "지식베이스에서 검색할 질의문") String query
	) {
		try {
			List<Document> results = vectorStore.similaritySearch(
				SearchRequest.builder().query(query).topK(5).similarityThreshold(0.5).build()
			);
			// [설계] 호출 여부·적중 건수를 로그로 남김 — Tool 전환 후 "모델이 도구를 호출했는가"가
			//        RAG 동작의 핵심 변수가 되므로, 로그 없이는 원인 추적이 불가능하다
			log.info("지식베이스 검색 — query: {}, 적중: {}건", query, results.size());
			if (results.isEmpty()) {
				// [설계] 0건일 때 인덱싱된 문서 목록을 대신 반환하는 이유:
				//   "방금 준 문서 뭐야?" 같은 메타 질문은 문서 '내용'과 의미적으로 유사하지 않아
				//   벡터 검색이 항상 0건을 낸다. 그대로 "못 찾았다"고만 답하면 문서가 멀쩡히
				//   인덱싱돼 있는데도 없다고 답하게 된다. 목록을 함께 주면 모델이
				//   "○○ 문서가 있습니다"라고 정확히 답할 수 있다.
				List<EtlSourceResponse> sources = etlSourceService.listSources();
				if (sources.isEmpty()) {
					return "지식베이스가 비어 있습니다. 인덱싱된 문서가 없습니다.";
				}
				String list = sources.stream()
					.map(s -> "- " + s.source() + " (" + s.chunkCount() + "청크)")
					.collect(Collectors.joining("\n"));
				return "질의와 직접 일치하는 내용은 찾지 못했습니다. 현재 인덱싱된 문서 목록:\n" + list;
			}
			return results.stream()
				.map(Document::getText)
				.collect(Collectors.joining("\n---\n"));
		} catch (Exception e) {
			// [설계] 임베딩 실패(NaN 등 Ollama 일시 오류) 시 도구 호출 자체가 예외로 죽지 않도록 흡수
			//        기존 SafeQuestionAnswerAdvisor의 graceful degradation과 동일한 취지
			log.warn("지식베이스 검색 실패 — query: {}, error: {}", query, e.getMessage());
			return "지식베이스 검색 중 오류가 발생했습니다. 일반 지식으로 답변하세요.";
		}
	}

	// [Tool 4] 사용량 조회 — 사용자가 "토큰 얼마나 썼어?" 등을 물을 때 LLM이 호출
	@Tool(name = "getTokenUsage",
		description = "현재 대화방의 누적 토큰 사용량을 조회합니다. " +
			"사용자가 토큰 사용량이나 대화 비용을 물어볼 때 사용하세요.")
	public String getTokenUsage(
		ToolContext toolContext   // [설계] LLM 파라미터 목록에 포함되지 않음 — Spring AI가 자동 주입
	) {
		String conversationId = (String) toolContext.getContext().get("conversationId");
		if (conversationId == null) {
			return "대화방 정보를 찾을 수 없습니다.";
		}

		return chatRoomRepository.findByConversationId(conversationId)
			.map(room -> {
				Long totalTokens = chatHistoryRepository.sumTotalTokensByChatRoomId(room.getId());
				long tokens = totalTokens != null ? totalTokens : 0L;
				// 실제 과금 환경이라면 단가 적용 — 현재는 토큰 수만 반환
				return "이 대화방의 누적 토큰 사용량: " + tokens + " 토큰";
			})
			.orElse("대화방을 찾을 수 없습니다.");
	}
}