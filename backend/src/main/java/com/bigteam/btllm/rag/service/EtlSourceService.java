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
    // [보안] 소유권 모델: 문서는 업로드한 사용자 개인 소유다. owner_id predicate로 다른 사용자의
    //   source를 아예 결과에서 걷어낸다 — 목록에 안 보이면 이름조차 알 수 없다.
    //   (metadata->>'owner_id')::bigint는 owner_id 키가 없는 레거시 행(이 기능 이전에 색인된
    //   문서)에서 NULL이 되어 자연히 제외된다 — 아무 사용자에게도 잘못 노출되지 않는다.
    private static final String LIST_SQL = """
            SELECT
                metadata->>'source'  AS source,
                metadata->>'type'    AS type,
                COUNT(*)::int        AS chunk_count
            FROM vector_store
            WHERE metadata->>'source' IS NOT NULL
              AND (metadata->>'owner_id')::bigint = ?
            GROUP BY metadata->>'source', metadata->>'type'
            ORDER BY source
            """;

    // [설계] source 값 일치 청크 전체 삭제 — metadata JSONB ->>'source' 직접 필터
    // [보안] owner_id predicate를 함께 걸어 다른 사용자의 source 이름을 넣어도 0건 삭제로 끝난다.
    private static final String DELETE_SQL =
        "DELETE FROM vector_store WHERE metadata->>'source' = ? AND (metadata->>'owner_id')::bigint = ?";

    // source별 집계 목록 반환 (호출자 소유 문서만)
    public List<EtlSourceResponse> listSources(Long ownerId) {
        return jdbcTemplate.query(LIST_SQL, (rs, rowNum) ->
            new EtlSourceResponse(
                rs.getString("source"),
                rs.getString("type"),
                rs.getInt("chunk_count")
            ),
            ownerId
        );
    }

    // source에 속한 청크 중 호출자 소유분만 삭제 → 삭제된 청크 수 반환
    @Transactional
    public int deleteSource(String source, Long ownerId) {
        int deleted = jdbcTemplate.update(DELETE_SQL, source, ownerId);
        log.info("벡터 DB 삭제 완료 — source: {}, ownerId: {}, 삭제 청크: {}", source, ownerId, deleted);
        return deleted;
    }
}
