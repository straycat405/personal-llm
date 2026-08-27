package com.bigteam.btllm.rag.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * [범위] P0 #3 사용자별 소유 문서 모델 — 목록/삭제 SQL이 owner_id를 바인드 파라미터로
 * 실제로 넘기는지 검증한다. 문자열 연결이 아니라 JdbcTemplate bind parameter를 쓰므로
 * SQL 인젝션 형태는 아니지만, predicate 자체가 빠지면 다른 사용자 문서가 노출/삭제된다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EtlSourceService 소유권 predicate 단위 테스트")
class EtlSourceServiceTest {

    @Mock JdbcTemplate jdbcTemplate;
    @InjectMocks EtlSourceService etlSourceService;

    private static final Long OWNER_ID = 42L;

    @Test
    @DisplayName("listSources는 ownerId를 SQL 파라미터로 전달한다")
    void listSourcesBindsOwnerId() {
        etlSourceService.listSources(OWNER_ID);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> param = ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), param.capture());

        assertThat(sql.getValue())
            .contains("metadata->>'owner_id'")
            .contains("WHERE metadata->>'source' IS NOT NULL");
        assertThat(param.getValue()).isEqualTo(OWNER_ID);
    }

    @Test
    @DisplayName("deleteSource는 source와 ownerId를 함께 SQL 파라미터로 전달한다")
    void deleteSourceBindsSourceAndOwnerId() {
        given(jdbcTemplate.update(anyString(), eq("문서.pdf"), eq(OWNER_ID))).willReturn(3);

        int deleted = etlSourceService.deleteSource("문서.pdf", OWNER_ID);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), eq("문서.pdf"), eq(OWNER_ID));
        assertThat(sql.getValue())
            .contains("metadata->>'source' = ?")
            .contains("metadata->>'owner_id'")
            .contains("AND");
        assertThat(deleted).isEqualTo(3);
    }
}
