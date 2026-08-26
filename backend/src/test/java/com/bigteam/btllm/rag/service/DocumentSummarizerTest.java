package com.bigteam.btllm.rag.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

@DisplayName("문서 개요 요약 청크 생성 테스트")
class DocumentSummarizerTest {

    private static final String LONG_TEXT = "모두의 창업 프로젝트 공고 본문입니다. ".repeat(50);

    private DocumentSummarizer summarizer;

    @BeforeEach
    void setUp() {
        // 생성자가 OllamaChatModel로 ChatClient를 만들므로, 만들어진 클라이언트를 교체해 검증한다.
        summarizer = new DocumentSummarizer(mock(OllamaChatModel.class, RETURNS_DEEP_STUBS));
    }

    private void givenSummaryResponse(String summary) {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        given(chatClient.prompt().user(anyString()).call().content()).willReturn(summary);
        ReflectionTestUtils.setField(summarizer, "chatClient", chatClient);
    }

    private void givenSummaryFailure() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        given(chatClient.prompt().user(anyString()).call().content())
            .willThrow(new IllegalStateException("Ollama 연결 실패"));
        ReflectionTestUtils.setField(summarizer, "chatClient", chatClient);
    }

    @Test
    @DisplayName("요약 청크에 문서명과 개요 표지를 붙이고 chunk_type을 표시한다")
    void createsSummaryChunkWithSourceHeader() {
        givenSummaryResponse("이 문서는 창업 지원 사업 공고이며 두 개 트랙으로 운영된다.");

        List<Document> result = summarizer.summarize(
            List.of(new Document(LONG_TEXT, Map.of("source", "창업공고.pdf", "type", "pdf"))),
            "창업공고.pdf");

        assertThat(result).hasSize(1);
        Document summary = result.get(0);
        assertThat(summary.getText())
            .contains("[문서 개요]")
            .contains("창업공고.pdf")
            .contains("두 개 트랙으로 운영된다");
        assertThat(summary.getMetadata())
            .containsEntry(DocumentSummarizer.CHUNK_TYPE_KEY, DocumentSummarizer.CHUNK_TYPE_SUMMARY)
            .containsEntry("source", "창업공고.pdf")
            .containsEntry("type", "pdf");   // 원본 메타데이터 승계
    }

    @Test
    @DisplayName("짧은 문서는 요약하지 않는다")
    void skipsShortDocuments() {
        List<Document> result = summarizer.summarize(
            List.of(new Document("짧은 메모", Map.of("source", "memo.txt"))), "memo.txt");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("요약 LLM 호출이 실패해도 예외를 던지지 않아 본문 색인을 막지 않는다")
    void absorbsSummarizationFailure() {
        givenSummaryFailure();

        List<Document> result = summarizer.summarize(
            List.of(new Document(LONG_TEXT, Map.of("source", "창업공고.pdf"))), "창업공고.pdf");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("요약 결과가 비어 있으면 청크를 만들지 않는다")
    void skipsBlankSummary() {
        givenSummaryResponse("   ");

        List<Document> result = summarizer.summarize(
            List.of(new Document(LONG_TEXT, Map.of("source", "창업공고.pdf"))), "창업공고.pdf");

        assertThat(result).isEmpty();
    }
}
