-- 대화 이력 본문 키워드 검색용 trigram GIN 인덱스
--
-- [적용]
--   docker exec -i btllm-postgres psql -U btllm -d btllm -f - < 이 파일
--   또는 psql 접속 후 그대로 붙여넣기
--
-- [왜 JPA @Index로 선언하지 않았나]
--   lower(content) 라는 표현식에 거는 인덱스라 @Index(columnList=...)로 표현할 수 없다.
--   컬럼 인덱스(chat_room_id, created_at, id)는 ChatHistory 엔티티에 선언돼 있다.
--
-- [왜 B-tree가 아닌 GIN + trigram인가]
--   검색 쿼리가 LIKE '%키워드%' 형태다. 앞에 와일드카드가 붙으면 B-tree는 시작 지점을
--   특정할 수 없어 인덱스를 쓸 수 없다. pg_trgm은 문자열을 3글자 조각으로 쪼개 GIN에
--   담으므로 중간 일치도 인덱스로 좁힐 수 있다.
--
-- [측정 — 20만 행 기준, 이 인덱스가 있고 없고의 차이]
--   전역 키워드 검색(매칭 0건):
--     인덱스 없음 : 57.0 ms · 2,844 buffers (Parallel Seq Scan, 200,036행 전부 필터)
--     인덱스 있음 :  0.03 ms ·    15 buffers (Bitmap Index Scan)
--
-- [주의 — 이 인덱스가 항상 쓰이지는 않는다]
--   방 필터 + LIMIT 5 + 자주 등장하는 키워드 조합에서는 플래너가 이 인덱스를 쓰지 않고
--   ix_chat_histories_room_created 로 정렬 순서대로 읽다가 5건을 채우고 멈추는 쪽을
--   택한다(28행만 읽고 종료). 그 편이 실제로 더 싸므로 올바른 선택이다.
--   이 인덱스가 결정적인 구간은 "매칭이 희소하거나 아예 없을 때"다 — 그때 복합 인덱스만
--   있으면 방 전체를 훑고 빈손으로 끝나기 때문이다.
--
-- [비용] 20만 행 기준 약 9.4 MB. 쓰기 시 GIN 갱신 부담이 있으므로,
--   대화 이력처럼 읽기가 압도적으로 많은 테이블에 한정해 사용한다.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS ix_chat_histories_content_trgm
    ON chat_histories USING gin (lower(content) gin_trgm_ops);

ANALYZE chat_histories;
