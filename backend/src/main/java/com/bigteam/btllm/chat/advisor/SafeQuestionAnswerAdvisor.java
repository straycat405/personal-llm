package com.bigteam.btllm.chat.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * [역할] QuestionAnswerAdvisor 래퍼 — 임베딩 실패 시 RAG 없이 진행(graceful degradation)
 *
 * [설계 결정사항]
 * - QuestionAnswerAdvisor 생성자가 package-private → 직접 상속 불가
 *   Builder로 생성한 인스턴스를 delegate로 래핑하는 방식 채택
 * - before()에서 예외 포획: NaN 임베딩 등 Ollama 일시적 오류로 스트림 전체 중단 방지
 * - 예외 발생 시 원본 request 그대로 반환 → RAG 컨텍스트 없이 LLM 호출 계속
 * - 운영 환경에서 임베딩 모델 교체·재시작 후 자동 회복
 */
@Slf4j
public class SafeQuestionAnswerAdvisor implements BaseAdvisor {

	// [설계] 기본 영어 템플릿 대체:
	//        - "context information" 영어 문구 → qwen2.5가 그대로 언급하는 문제 방지
	//        - context 먼저, query 나중: 비어 있으면 모델에게 쿼리만 보임 → "없음" 언급 방지
	//          내용 있으면 자연스럽게 RAG 청크가 질문 앞에 선행되어 참조됨
	private static final String KO_PROMPT_TEMPLATE = """
		{question_answer_context}

		{query}
		""";

	private final QuestionAnswerAdvisor delegate;

	public SafeQuestionAnswerAdvisor(VectorStore vectorStore, SearchRequest searchRequest, int order) {
		// [설계] Builder 경유 생성: QuestionAnswerAdvisor 생성자가 package-private이라 직접 호출 불가
		this.delegate = QuestionAnswerAdvisor.builder(vectorStore)
			.searchRequest(searchRequest)
			.promptTemplate(new PromptTemplate(KO_PROMPT_TEMPLATE))
			.order(order)
			.build();
	}

	@Override
	public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
		try {
			// [설계] delegate.before()가 벡터 DB 유사도 검색 수행 — NaN 임베딩 시 예외 발생 지점
			return delegate.before(request, chain);
		} catch (Exception e) {
			// [설계] 임베딩 실패 = RAG 컨텍스트 없음으로 처리, 스트림 중단 방지
			log.warn("RAG 임베딩 실패, RAG 없이 진행 — error: {}", e.getMessage());
			return request;
		}
	}

	@Override
	public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
		// [설계] after()는 retrieved docs를 response에 메타데이터로 추가하는 역할
		//        before()에서 RAG 생략됐어도 delegate 호출은 안전 (빈 상태로 처리됨)
		return delegate.after(response, chain);
	}

	@Override
	public int getOrder() {
		return delegate.getOrder();
	}

	@Override
	public String getName() {
		return "SafeQuestionAnswerAdvisor";
	}
}
