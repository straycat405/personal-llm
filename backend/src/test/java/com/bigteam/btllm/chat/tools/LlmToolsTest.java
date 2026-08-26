package com.bigteam.btllm.chat.tools;

import com.bigteam.btllm.chat.repository.ChatHistoryRepository;
import com.bigteam.btllm.chat.repository.ChatRoomRepository;
import com.bigteam.btllm.rag.dto.EtlSourceResponse;
import com.bigteam.btllm.rag.service.EtlSourceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("LlmTools 지식베이스 검색 테스트")
class LlmToolsTest {

    @Mock ChatRoomRepository chatRoomRepository;
    @Mock ChatHistoryRepository chatHistoryRepository;
    @Mock VectorStore vectorStore;
    @Mock EtlSourceService etlSourceService;

    @InjectMocks LlmTools llmTools;

    @Test
    @DisplayName("검색 결과를 관련도 순위·출처·청크 위치와 함께 반환한다")
    void formatsRankedKnowledgeResults() {
        Document first = new Document("사업 목적과 두 개 트랙 설명", Map.of(
            "source", "창업공고.pdf", "chunk_index", 0, "total_chunks", 21));
        Document second = new Document("신청 제한 사항", Map.of(
            "source", "창업공고.pdf", "chunk_index", "8", "total_chunks", "21"));
        given(vectorStore.similaritySearch(any(SearchRequest.class)))
            .willReturn(List.of(first, second));

        String result = llmTools.searchKnowledgeBase("프로젝트를 설명해줘");

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
        assertThat(request.getValue().getTopK()).isEqualTo(5);
        assertThat(request.getValue().getSimilarityThreshold()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("검색 결과가 없으면 현재 인덱싱된 문서 목록을 반환한다")
    void returnsSourceListWhenSearchHasNoHits() {
        given(vectorStore.similaritySearch(any(SearchRequest.class))).willReturn(List.of());
        given(etlSourceService.listSources()).willReturn(List.of(
            new EtlSourceResponse("창업공고.pdf", "file", 21)));

        String result = llmTools.searchKnowledgeBase("방금 올린 문서가 뭐야?");

        assertThat(result)
            .contains("질의와 직접 일치하는 내용은 찾지 못했습니다")
            .contains("- 창업공고.pdf (21청크)");
    }

    @Test
    @DisplayName("메타데이터가 없어도 출처 미상으로 안전하게 포맷한다")
    void handlesMissingMetadata() {
        given(vectorStore.similaritySearch(any(SearchRequest.class)))
            .willReturn(List.of(new Document("근거 본문")));

        String result = llmTools.searchKnowledgeBase("질문");

        assertThat(result)
            .contains("[관련도 1위]")
            .contains("출처: 출처 미상")
            .doesNotContain("문서 내 위치:");
    }
}
