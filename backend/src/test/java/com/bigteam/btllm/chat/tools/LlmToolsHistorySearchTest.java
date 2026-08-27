package com.bigteam.btllm.chat.tools;

import com.bigteam.btllm.chat.entity.ChatHistory;
import com.bigteam.btllm.chat.entity.ChatRoom;
import com.bigteam.btllm.chat.entity.MessageRole;
import com.bigteam.btllm.chat.repository.ChatHistoryRepository;
import com.bigteam.btllm.chat.repository.ChatRoomRepository;
import com.bigteam.btllm.common.net.SafeUrlFetcher;
import com.bigteam.btllm.rag.service.EtlSourceService;
import com.bigteam.btllm.rag.service.HybridReranker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * [범위] 이력 검색 결과 상한이 SQL LIMIT으로 내려가는지 고정한다.
 *
 * 예전 구현은 매칭 전체를 조회한 뒤 Java 스트림에서 .limit(5)로 잘랐다. 결과가 같아 보여
 * 회귀를 눈으로 잡기 어렵지만, 버릴 행까지 DB가 정렬해 전송하므로 매칭이 많은 방일수록
 * 낭비가 그대로 커진다. 그래서 "Pageable이 실제로 넘어가는가"를 테스트로 못박는다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LlmTools 이력 검색 — 상한을 SQL로 내리는지 검증")
class LlmToolsHistorySearchTest {

    @Mock ChatRoomRepository chatRoomRepository;
    @Mock ChatHistoryRepository chatHistoryRepository;
    @Mock VectorStore vectorStore;
    @Mock EtlSourceService etlSourceService;
    @Mock SafeUrlFetcher safeUrlFetcher;
    @Spy HybridReranker hybridReranker = new HybridReranker();

    @InjectMocks LlmTools llmTools;

    private static final String CONV_ID = "conv-uuid-1234";
    private static final ToolContext CONTEXT =
        new ToolContext(Map.of("conversationId", CONV_ID, "userId", 1L));

    private ChatRoom room() {
        ChatRoom r = ChatRoom.builder().title("t").conversationId(CONV_ID).build();
        ReflectionTestUtils.setField(r, "id", 7L);
        return r;
    }

    private ChatHistory history(String content) {
        return ChatHistory.builder()
            .role(MessageRole.USER)
            .content(content)
            .build();
    }

    @Test
    @DisplayName("조회 상한을 Pageable로 전달한다 — Java에서 자르지 않는다")
    void pushesLimitIntoTheQuery() {
        given(chatRoomRepository.findByConversationId(CONV_ID)).willReturn(Optional.of(room()));
        given(chatHistoryRepository.findByChatRoomIdAndKeyword(anyLong(), anyString(), any(Pageable.class)))
            .willReturn(List.of(history("임베딩 관련 대화")));

        llmTools.searchChatHistory("임베딩", CONTEXT);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(chatHistoryRepository).findByChatRoomIdAndKeyword(
            org.mockito.ArgumentMatchers.eq(7L),
            org.mockito.ArgumentMatchers.eq("임베딩"),
            pageable.capture());

        assertThat(pageable.getValue().getPageSize()).isEqualTo(5);
        assertThat(pageable.getValue().getPageNumber()).isZero();
    }

    @Test
    @DisplayName("리포지토리가 돌려준 건수를 그대로 쓴다 — 추가 절단이 없다")
    void doesNotTruncateAgainInJava() {
        given(chatRoomRepository.findByConversationId(CONV_ID)).willReturn(Optional.of(room()));
        // 상한이 SQL로 내려갔으므로 리포지토리는 이미 5건만 돌려준다.
        List<ChatHistory> five = IntStream.rangeClosed(1, 5)
            .mapToObj((i) -> history("메시지 " + i))
            .toList();
        given(chatHistoryRepository.findByChatRoomIdAndKeyword(anyLong(), anyString(), any(Pageable.class)))
            .willReturn(five);

        String result = llmTools.searchChatHistory("메시지", CONTEXT);

        for (int i = 1; i <= 5; i++) {
            assertThat(result).contains("메시지 " + i);
        }
    }

    @Test
    @DisplayName("매칭이 없으면 안내 문구를 돌려준다")
    void reportsNoMatch() {
        given(chatRoomRepository.findByConversationId(CONV_ID)).willReturn(Optional.of(room()));
        given(chatHistoryRepository.findByChatRoomIdAndKeyword(anyLong(), anyString(), any(Pageable.class)))
            .willReturn(List.of());

        assertThat(llmTools.searchChatHistory("없는키워드", CONTEXT))
            .contains("찾을 수 없습니다");
    }
}
