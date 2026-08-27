package com.bigteam.btllm.rag.controller;

import com.bigteam.btllm.common.jwt.AuthUser;
import com.bigteam.btllm.common.jwt.JwtProvider;
import com.bigteam.btllm.rag.dto.EtlSourceResponse;
import com.bigteam.btllm.rag.service.EtlPipelineService;
import com.bigteam.btllm.rag.service.EtlProgressTracker;
import com.bigteam.btllm.rag.service.EtlSourceService;
import com.bigteam.btllm.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * [범위] P0 #3 사용자별 소유 문서 모델 — 컨트롤러가 인증된 사용자의 id를
 * 서비스 계층으로 정확히 전달하는지 검증한다(실제 소유권 필터링은 EtlSourceServiceTest).
 */
@WebMvcTest(EtlController.class)
@DisplayName("EtlController MockMvc 테스트 — 소유권 전달")
class EtlControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean EtlPipelineService etlPipelineService;
    @MockitoBean EtlProgressTracker tracker;
    @MockitoBean EtlSourceService etlSourceService;
    // [설계] SecurityConfig가 JwtProvider/UserRepository 생성자 주입 → @MockitoBean으로 의존성 충족
    @MockitoBean JwtProvider jwtProvider;
    @MockitoBean UserRepository userRepository;

    private static final Long USER_ID = 42L;

    private RequestPostProcessor asUser() {
        AuthUser authUser = new AuthUser(USER_ID, "user@test.com");
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
            authUser, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        return authentication(auth);
    }

    @Nested
    @DisplayName("GET /api/v1/admin/etl/sources")
    class ListSources {

        @Test
        @DisplayName("인증된 사용자 id로 목록을 조회한다")
        void listsOwnSources() throws Exception {
            given(etlSourceService.listSources(USER_ID)).willReturn(
                List.of(new EtlSourceResponse("문서.pdf", "pdf", 5)));

            mockMvc.perform(get("/api/v1/admin/etl/sources").with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].source").value("문서.pdf"));

            then(etlSourceService).should().listSources(USER_ID);
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/admin/etl/sources")
    class DeleteSource {

        @Test
        @DisplayName("인증된 사용자 id를 소유권 predicate로 함께 전달한다")
        void deletesWithOwnerPredicate() throws Exception {
            given(etlSourceService.deleteSource(eq("문서.pdf"), eq(USER_ID))).willReturn(5);

            mockMvc.perform(delete("/api/v1/admin/etl/sources")
                    .param("source", "문서.pdf")
                    .with(asUser()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(5));

            then(etlSourceService).should().deleteSource("문서.pdf", USER_ID);
        }
    }

    @Nested
    @DisplayName("POST /api/v1/admin/etl/url")
    class IngestUrl {

        @Test
        @DisplayName("인증된 사용자 id를 ownerId로 파이프라인에 전달한다")
        void ingestsWithOwnerId() throws Exception {
            mockMvc.perform(post("/api/v1/admin/etl/url")
                    .with(asUser()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("url", "https://example.com"))))
                .andExpect(status().isAccepted());

            then(etlPipelineService).should()
                .ingestUrlAsync(eq("https://example.com"), org.mockito.ArgumentMatchers.anyString(), eq(USER_ID));
        }
    }
}
