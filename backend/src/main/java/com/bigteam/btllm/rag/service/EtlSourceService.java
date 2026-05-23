package com.bigteam.btllm.rag.service;

import com.bigteam.btllm.rag.dto.EtlSourceResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class EtlSourceService {

    private final JdbcTemplate jdbcTemplate;

    // [설계] Spring AI PgVectorStore 기본 테이블 'vector_store'에 직접 쿼리
    //   VectorStore 인터페이스가 메타데이터 집계 조회를 미지원 → JdbcTemplate 사용
    private static final String LIST_SQL = """
            SELECT
                metadata->>'source'  AS source,
                metadata->>'type'    AS type,
                COUNT(*)::int        AS chunk_count
            FROM vector_store
            WHERE metadata->>'source' IS NOT NULL
            GROUP BY metadata->>'source', metadata->>'type'
            ORDER BY source
            """;

    // [설계] source 값 일치 청크 전체 삭제 — metadata JSONB ->>'source' 직접 필터
    private static final String DELETE_SQL =
        "DELETE FROM vector_store WHERE metadata->>'source' = ?";

    // source별 집계 목록 반환
    public List<EtlSourceResponse> listSources() {
        return jdbcTemplate.query(LIST_SQL, (rs, rowNum) ->
            new EtlSourceResponse(
                rs.getString("source"),
                rs.getString("type"),
                rs.getInt("chunk_count")
            )
        );
    }

    // source에 속한 모든 청크 삭제 → 삭제된 청크 수 반환
    @Transactional
    public int deleteSource(String source) {
        int deleted = jdbcTemplate.update(DELETE_SQL, source);
        log.info("벡터 DB 삭제 완료 — source: {}, 삭제 청크: {}", source, deleted);
        return deleted;
    }
}
