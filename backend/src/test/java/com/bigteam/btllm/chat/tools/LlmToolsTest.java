package com.bigteam.btllm.chat.tools;

import com.bigteam.btllm.chat.repository.ChatHistoryRepository;
import com.bigteam.btllm.chat.repository.ChatRoomRepository;
import com.bigteam.btllm.common.net.SafeUrlException;
import com.bigteam.btllm.common.net.SafeUrlFetcher;
import com.bigteam.btllm.rag.config.RagSearchSettings;
import com.bigteam.btllm.rag.dto.EtlSourceResponse;
import com.bigteam.btllm.rag.service.EtlSourceService;
import com.bigteam.btllm.rag.service.HybridReranker;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("LlmTools 지식베이스 검색 테스트")
class LlmToolsTest {

    @Mock ChatRoomRepository chatRoomRepository;
    @Mock ChatHistoryRepository chatHistoryRepository;
    @Mock VectorStore vectorStore;
    @Mock EtlSourceService etlSourceService;
    @Mock SafeUrlFetcher safeUrlFetcher;
    @Spy HybridReranker hybridReranker = new HybridReranker();

    @InjectMocks LlmTools llmTools;

    // [설계] P0 #3 사용자별 소유 문서 모델 — searchKnowledgeBase가 owner_id 필터를 걸려면
    // toolContext에 userId가 있어야 한다. ChatWebSocketHandler가 실제로 주입하는 값과 동일한 키.
    private static final ToolContext OWNER_CONTEXT = new ToolContext(Map.of("userId", 1L));

    @Test
    @DisplayName("검색 결과를 관련도 순위·출처·청크 위치와 함께 반환한다")
    void formatsRankedKnowledgeResults() {
        Document first = new Document("사업 목적과 두 개 트랙 설명", Map.of(
            "source", "창업공고.pdf", "chunk_index", 0, "total_chunks", 21));
        Document second = new Document("신청 제한 사항", Map.of(
            "source", "창업공고.pdf", "chunk_index", "8", "total_chunks", "21"));
        given(vectorStore.similaritySearch(any(SearchRequest.class)))
            .willReturn(List.of(first, second));

        String result = llmTools.searchKnowledgeBase("프로젝트를 설명해줘", OWNER_CONTEXT);

        assertThat(result)
            .contains("[지식베이스 검색 결과]")
            .contains("[관련도 1위]\n출처: 창업공고.pdf\n문서 내 위치: 1/21 청크")
            .contains("[관련도 2위]\n출처: 창업공고.pdf\n문서 내 위치: 9/21 청크")
            .contains("문서에 없는 내용은 추측하지 말고")
            .contains("[검색 결과 끝]")
            .contains("[답변 작성 규칙]")
            .contains("마지막 줄은 반드시 `[출처] 실제 사용한 파일명`");
        assertThat(result.indexOf("사업 목적과 두 개 트랙 설명"))
            .isLessThan(result.indexOf("신청 제한 사항"));

        ArgumentCaptor<SearchRequest> request = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(request.capture());
        assertThat(request.getValue().getTopK()).isEqualTo(RagSearchSettings.RERANK_CANDIDATE_K);
        assertThat(request.getValue().getSimilarityThreshold()).isEqualTo(0.5);
        // [보안] P0 #3 — 검색이 toolContext의 userId로 owner_id 필터를 실제로 거는지 확인.
        //   이 필터가 빠지면 모든 사용자의 문서가 검색 대상이 된다.
        assertThat(request.getValue().getFilterExpression().toString())
            .contains("owner_id")
            .contains("1");
    }

    @Test
    @DisplayName("toolContext에 userId가 없으면 검색을 거부한다 (fail-closed)")
    void refusesSearchWhenOwnerIdMissing() {
        String result = llmTools.searchKnowledgeBase("질문", new ToolContext(Map.of()));

        assertThat(result).contains("오류가 발생했습니다");
        verify(vectorStore, org.mockito.Mockito.never()).similaritySearch(any(SearchRequest.class));
    }

    @Test
    @DisplayName("검색 결과가 없으면 현재 인덱싱된 문서 목록을 반환한다")
    void returnsSourceListWhenSearchHasNoHits() {
        given(vectorStore.similaritySearch(any(SearchRequest.class))).willReturn(List.of());
        given(etlSourceService.listSources(1L)).willReturn(List.of(
            new EtlSourceResponse("창업공고.pdf", "file", 21)));

        String result = llmTools.searchKnowledgeBase("방금 올린 문서가 뭐야?", OWNER_CONTEXT);

        assertThat(result)
            .contains("질의와 직접 일치하는 내용은 찾지 못했습니다")
            .contains("- 창업공고.pdf (21청크)");
    }

    @Test
    @DisplayName("메타데이터가 없어도 출처 미상으로 안전하게 포맷한다")
    void handlesMissingMetadata() {
        given(vectorStore.similaritySearch(any(SearchRequest.class)))
            .willReturn(List.of(new Document("근거 본문")));

        String result = llmTools.searchKnowledgeBase("질문", OWNER_CONTEXT);

        assertThat(result)
            .contains("[관련도 1위]")
            .contains("출처: 출처 미상")
            .doesNotContain("문서 내 위치:");
    }

    @Nested
    @DisplayName("crawlWebPage — SafeUrlFetcher 경유")
    class CrawlWebPage {

        @Test
        @DisplayName("SafeUrlFetcher가 반환한 본문 텍스트를 그대로 전달한다")
        void delegatesToSafeUrlFetcher() throws Exception {
            org.jsoup.nodes.Document parsed = Jsoup.parse("<html><body>안녕하세요</body></html>");
            given(safeUrlFetcher.fetch(eq("https://example.com"), any(SafeUrlFetcher.FetchOptions.class)))
                .willReturn(parsed);

            String result = llmTools.crawlWebPage("https://example.com");

            assertThat(result).isEqualTo("안녕하세요");
        }

        @Test
        @DisplayName("SSRF 차단(SafeUrlException) 시 사용자에게 실패 메시지로 안전하게 안내한다")
        void surfacesSafeUrlExceptionAsFailureMessage() throws Exception {
            given(safeUrlFetcher.fetch(anyString(), any(SafeUrlFetcher.FetchOptions.class)))
                .willThrow(new SafeUrlException("사설/루프백/링크로컬 등 접근이 차단된 IP를 가리키는 호스트입니다: 169.254.169.254"));

            String result = llmTools.crawlWebPage("http://169.254.169.254/latest/meta-data/");

            assertThat(result).contains("페이지를 가져오는 데 실패했습니다");
        }
    }
}
