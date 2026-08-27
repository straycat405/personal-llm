# 인수인계 문서

최종 갱신일: 2026-08-27
작업 경로: `C:\Users\User\claude-project\personal-llm-remaster`
저장소: https://github.com/straycat405/personal-llm (브랜치 `main`)

---

## ⏭️ 다음 세션은 여기서 시작 (2026-08-27, Claude Sonnet 5 세션 갱신)

**궁극적 목표**: 하드웨어 제약(RTX 4060 Ti 8GB, Ollama 단일 GPU 슬롯) 안에서
로컬 LLM의 응답 품질과 체감 성능을 함께 개선한다. 다만 사용자가 PDF 구현을 잠시 중단하고,
그동안 쌓인 코드의 **보안·성능·안정성·유지보수 보수작업을 먼저 하라**고 지시했다.

**현재 지시**: 새 기능이나 PDF 파서 개선으로 돌아가지 말 것. 아래의 2026-08-27 보수 진단을
기준으로 P0부터 수정·검증한다.

**P0 진행 상황**:

1. ✅ **완료** — P0 보안 #1: Compose JWT 기본 키 제거와 시작 실패 검증 (아래 "P0 #1 완료 기록" 참고)
2. ✅ **완료** — P0 보안 #2: Docker DB·Grafana·Prometheus·Loki 포트/기본 계정 잠금 (아래 "P0 #2 완료 기록" 참고)
3. ✅ **완료** — P0 보안 #3: `/api/v1/admin/etl/**` 권한 정책과 RAG 소유권 모델 결정·적용 (아래 "P0 #3 완료 기록" 참고, 사용자가 "사용자별 소유 문서"로 결정)
4. ✅ **완료** — P0 보안 #4: ETL 및 LLM 크롤러 SSRF 공통 차단기 구현 (아래 "P0 #4 완료 기록" 참고) — **P0 전체 완료**
5. ✅ **완료** — P1 #5: GPU admission control(채팅 생성 단일 슬롯 큐 + 세션당 in-flight 1개 + 취소) (아래 "P1 #5 완료 기록" 참고)
6. ⏭ **다음** — P1 #6: ETL bounded executor/파서 예산 (P1 #5 큐와의 통합 여부 결정 필요 — 아래 참고)
7. P1 #7: provider/model·비용 통제 (서버 allowlist, cache 상한, 외부 provider 토큰·동시성 한도)
8. P1 #8: SSE/progress 수명 관리 (raw thread 제거, owner 검증, TTL cleanup, subscriber 제한)
9. P2 유지보수: 스트리밍 문자열 누적, 검색 쿼리·페이지네이션, DB 마이그레이션·운영 프로필
10. 보수 기준선이 안정된 뒤에만 미커밋 PDF 파서 실험 재개

**저장소 상태**:

- 브랜치: `main`
- `HEAD` = `origin/main` = `5b08581`
- 작업 트리는 **깨끗하지 않다.** PDF 표 구조 보존 실험과 평가 하네스 변경이 미커밋 상태다.
- 이 미커밋 변경은 사용자 작업으로 간주한다. 보수작업 중 덮어쓰기·되돌리기·삭제·혼합 커밋 금지.
- `tmp/` 아래 PDF 진단 이미지 등 미추적 파일도 보존한다.

### P0 #1 완료 기록 (Compose JWT 기본 키 제거·시작 실패)

**변경 파일**: [docker-compose.yml](docker-compose.yml), [.env.example](.env.example) (신규),
[JwtProvider.java](backend/src/main/java/com/bigteam/btllm/common/jwt/JwtProvider.java),
[JwtAuthFilter.java](backend/src/main/java/com/bigteam/btllm/common/jwt/JwtAuthFilter.java),
[ChatWebSocketHandler.java](backend/src/main/java/com/bigteam/btllm/chat/controller/ChatWebSocketHandler.java),
[SecurityConfig.java](backend/src/main/java/com/bigteam/btllm/config/SecurityConfig.java),
[ci.yml](.github/workflows/ci.yml), [README.md](README.md),
테스트 `JwtProviderTest`/`JwtAuthFilterTest`(신규), `ChatRoomControllerTest`(UserRepository mock 추가).

- `docker-compose.yml`의 `JWT_SECRET:-btllm-dev-secret-change-in-production` 공개 fallback 삭제 →
  `${JWT_SECRET:?...}`로 변경. `JWT_SECRET` 미설정 시 `docker compose up`/`config`가 **기동 자체를 거부**함을
  `docker compose config` 실제 실행으로 확인(실패/성공 양쪽). 루트 `.env.example` 신규 추가, README에
  `openssl rand -base64 32` 생성 안내 추가.
- `JwtProvider`에 secret 검증 추가: null/blank, 32바이트(256비트) 미만, 알려진 placeholder
  (`btllm-dev-secret-change-in-production`, `changeme`, `secret` 등) 모두 `IllegalStateException`으로
  기동 실패. 직접 실행 시 `application.yaml`의 `${JWT_SECRET:${random.value}}` fallback(32자 랜덤값)은
  그대로 통과 확인.
- `JwtAuthFilter`·`ChatWebSocketHandler` 둘 다 JWT subject(userId)를 `UserRepository.existsById()`로
  대조하도록 변경 — 서명은 유효하지만 탈퇴/삭제된 계정의 토큰은 인증되지 않음. `SecurityConfig`가
  `UserRepository`를 `JwtAuthFilter`에 주입하도록 수정.
- CI(`ci.yml`)의 `JWT_SECRET: test-secret-for-ci-only`(17바이트, 새 최소치 미달)를 51바이트 값으로 교체.
- 테스트: `JwtProviderTest`(거부/허용 케이스 8종), `JwtAuthFilterTest`(탈퇴 사용자 미인증·정상 사용자 인증)
  신규 작성. `./gradlew test` 전체 통과 확인(실험 태그 제외, CI와 동일 조건).
- **미적용/후속 과제**: 기존에 이미 발급된 토큰의 회전·무효화는 시크릿 교체 자체로 자동 해결됨(서명 불일치로
  전부 무효화). 별도 revoke 리스트나 refresh token은 이번 범위 밖.
- PDF 표 구조 실험(미커밋)은 건드리지 않음 — `git status`로 확인, 별도 유지.

### P0 #2 완료 기록 (Docker 포트 loopback 바인딩·기본 계정 제거)

**변경 파일**: [docker-compose.yml](docker-compose.yml), [.env.example](.env.example), [README.md](README.md).

- `db`(5433)·`prometheus`(9090)·`grafana`(3000)·`loki`(3100) 네 서비스 모두 host 포트를
  `127.0.0.1:<port>:<port>`로 바인딩 — LAN/외부에서 접근 불가, 로컬 개발자 접속만 가능.
  `backend`의 8080은 의도된 서비스 진입점이라 손대지 않음(JWT 인증으로 이미 보호됨, HANDOFF
  P0 #2 범위도 DB·관측 스택으로 한정).
  - 서비스 간 통신(Grafana→Prometheus/Loki `http://prometheus:9090`, `http://loki:3100`,
    backend→db `db:5432`)은 host 포트가 아니라 Docker 내부 네트워크(서비스명)로 이루어짐을
    `monitoring/grafana/provisioning/datasources/datasources.yml`로 확인 — loopback 바인딩이
    내부 기능을 깨지 않음.
- `POSTGRES_PASSWORD`: 기존 하드코딩된 `btllm1234` 제거, `db`·`backend` 두 서비스가 동일 env var를
  공유하도록 변경. JWT_SECRET과 동일한 `${VAR:?...}` 안전 실패 패턴.
- `GRAFANA_ADMIN_PASSWORD`: 기존 하드코딩된 `admin/admin` 중 비밀번호를 env var로 전환(안전 실패).
  `GF_SECURITY_ADMIN_USER: admin`은 그대로 둠 — 사용자명은 비밀이 아니라 식별자.
- Prometheus 커맨드 플래그에서 `--web.enable-lifecycle`(POST /-/reload 핫리로드)과
  `--web.enable-remote-write-receiver`(k6 remote write 수신) 제거 — 불필요한 control endpoint.
  `--web.enable-remote-write-receiver`는 `k6/*.js` 전체를 grep해도 실제로 쓰는 스크립트가
  없는 죽은 설정이었음을 확인 후 제거. lifecycle은 컨테이너 재시작으로 대체 가능.
- **의도적으로 그대로 둔 것**: Loki `auth_enabled: false`. 멀티테넌트 인증 도입은 단일 개발자
  로컬 환경 규모 대비 과한 인프라라고 판단, loopback 바인딩으로 대체. 외부 노출 시에는 반드시
  auth 활성화나 리버스 프록시 인증이 필요하다고 주석·HANDOFF 양쪽에 명시.
- 검증: `docker compose config`로 (a) `JWT_SECRET`/`POSTGRES_PASSWORD`/`GRAFANA_ADMIN_PASSWORD`
  각각 단독 누락 시 개별 실패 메시지 확인 (b) 세 값 모두 설정 시 성공 + 렌더링된 설정에서 4개 포트
  전부 `host_ip: 127.0.0.1`인지 실측 확인. 코드 변경 없어(compose/문서만) 백엔드 테스트 재실행 불필요.
- **후속 과제**: DB 비밀번호 회전 시 `postgres_data` 볼륨의 기존 비밀번호와 불일치하면 컨테이너가
  뜨지 않는다(Postgres는 최초 initdb 시점 비밀번호만 반영) — 기존 로컬 볼륨 재사용 시 볼륨을
  삭제하거나 `ALTER USER`로 맞춰야 함. 이번 세션에서는 문서화만 하고 실제 볼륨 마이그레이션은
  하지 않음(사용자 로컬 데이터 삭제 판단은 사용자 몫).

### P0 #3 완료 기록 (RAG 소유권 모델 — 사용자별 소유 문서로 결정)

**사용자 결정**: "관리자 전용 공유 KB" vs "사용자별 소유 문서" 중 **사용자별 소유 문서**를 선택.

**변경 파일**: [EtlPipelineService.java](backend/src/main/java/com/bigteam/btllm/rag/service/EtlPipelineService.java),
[EtlController.java](backend/src/main/java/com/bigteam/btllm/rag/controller/EtlController.java),
[EtlSourceService.java](backend/src/main/java/com/bigteam/btllm/rag/service/EtlSourceService.java),
[LlmTools.java](backend/src/main/java/com/bigteam/btllm/chat/tools/LlmTools.java),
[ChatWebSocketHandler.java](backend/src/main/java/com/bigteam/btllm/chat/controller/ChatWebSocketHandler.java),
신규 테스트 `EtlSourceServiceTest`·`EtlControllerTest`, `LlmToolsTest`/`ReindexDocumentTask`/
`ProviderComparisonExperiment`/`RagGenerationQualityExperiment` 시그니처 변경 대응.

- **데이터 모델**: 색인 시점에 청크·요약 Document 전체 metadata에 `owner_id`(Long, 업로더의
  JWT-검증된 userId)를 찍는다. `EtlPipelineService.pipelineWithProgress`에서 분할·요약이 끝난
  최종 `toIndex` 목록에 한 번에 찍도록 했다 — 리더(PagePdfDocumentReader/TikaDocumentReader/
  URL Document)마다 제각각 metadata를 세팅하는 지점을 건드리지 않아도 되게 하려는 선택.
- **ingest(POST /url·/pdf·/file)**: `@AuthenticationPrincipal AuthUser`에서 얻은 id를 그대로
  ownerId로 사용 — 클라이언트가 값을 지정할 수 없다(스푸핑 불가).
- **list/delete(GET·DELETE /sources)**: `EtlSourceService`의 SQL에
  `AND (metadata->>'owner_id')::bigint = ?` predicate 추가. 이 기능 이전에 색인된(owner_id
  키 자체가 없는) 레거시 행은 캐스트 결과가 NULL이 되어 **아무에게도 보이지 않는다** — 누구
  것인지 추측해서 배정하지 않고, 안전한 쪽(숨김)으로 처리했다. 재검색 재인덱싱하면 새 소유권이
  붙는다.
- **search(searchKnowledgeBase 챗 도구)**: `ToolContext`로 userId를 받아 `FilterExpressionBuilder
  .eq("owner_id", ownerId)`를 벡터 검색에 건다. **userId가 없으면 검색 자체를 거부**한다
  (fail-closed) — 필터 없는 검색으로 조용히 빠지면 전체 사용자 문서가 노출되므로 "실패 시 열림"
  대신 "실패 시 닫힘"을 택했다. `ChatWebSocketHandler`가 이미 P0 #1에서 DB로 검증한 userId를
  `toolContext`에 `conversationId`와 함께 실어 보낸다.
- **의도적으로 그대로 둔 것**: `/{jobId}/progress` SSE는 permitAll 그대로 — EventSource가 커스텀
  헤더를 못 보내 이 경로에서는 신원을 얻을 방법이 없다. progress 메시지는 파일명·본문 없이
  퍼센트/상태 문구뿐이라 노출 시 피해가 작다고 판단, 신원 있는 SSE(서명 티켓 등)는 P1 #8로 남김.
- **경로 이름(`/api/v1/admin/etl/**`)은 그대로 둠**: 더 이상 "admin 전용"이 아니라 사용자별
  엔드포인트가 됐지만, 경로 변경은 프론트(`frontend/src/api/etl.ts`)까지 건드리는 별도 변경이라
  이번 보안 수정에는 안 섞었다. 오해 소지가 있는 이름이라는 점만 남겨둔다(cosmetic, non-security).
- **실험/유틸 코드 보정**: `ReindexDocumentTask`(REST 우회 재색인 유틸)와
  `ProviderComparisonExperiment`/`RagGenerationQualityExperiment`(실 Ollama 골든셋 평가)는 JWT 없이
  파이프라인을 직접 호출하므로 실제 인증 사용자가 없다 — 로컬 테스트 계정(`persisttest@test.com`,
  `USER_ID=1L`, `ChatRoomControllerTest`와 동일 관례)으로 고정했다. **다른 계정으로 색인했다면
  이 상수들을 맞춰야 실험이 통과한다.**
- **검증**: `./gradlew compileJava compileTestJava test` 전체 통과(신규 4개 테스트 포함, CI와 동일
  조건). `EtlSourceServiceTest`로 SQL bind parameter를, `EtlControllerTest`로 컨트롤러→서비스
  ownerId 전달을, `LlmToolsTest`로 filterExpression 실제 적용과 fail-closed 케이스를 확인했다 —
  실제 Ollama/pgVector가 필요한 end-to-end 교차 사용자 접근 테스트(계정 A로 올리고 계정 B로 조회
  시도)는 하지 않았다, 필요하면 다음 세션에서 수동 검증 권장.
- **미커밋 PDF 실험과의 충돌 처리**: `EtlPipelineService.java`·`ProviderComparisonExperiment.java`·
  `RagGenerationQualityExperiment.java` 세 파일은 이미 PDF 표 구조 실험으로 미커밋 상태였다.
  `git stash`로 PDF 실험 diff만 분리 → 클린 베이스에 P0 #3 변경만 커밋 → 실험 diff를 다시
  pop해 병합(자동 병합, 충돌 없음)하는 방식으로 두 작업을 커밋 히스토리에서 분리했다. 병합 후
  전체 컴파일 재확인 완료.

### P0 #4 완료 기록 (ETL·LLM 크롤러 SSRF 공통 차단기) — **P0 전체 완료**

**신규 파일**: [SafeUrlFetcher.java](backend/src/main/java/com/bigteam/btllm/common/net/SafeUrlFetcher.java),
[SafeUrlException.java](backend/src/main/java/com/bigteam/btllm/common/net/SafeUrlException.java),
테스트 [SafeUrlFetcherTest.java](backend/src/test/java/com/bigteam/btllm/common/net/SafeUrlFetcherTest.java).
**변경 파일**: [EtlPipelineService.java](backend/src/main/java/com/bigteam/btllm/rag/service/EtlPipelineService.java)
(`ingestUrlAsync`), [LlmTools.java](backend/src/main/java/com/bigteam/btllm/chat/tools/LlmTools.java)
(`crawlWebPage`), `LlmToolsTest.java` 케이스 추가.

- **공통 계층**: `SafeUrlFetcher` 하나를 두 호출부(ETL URL 수집, LLM 크롤러 Tool)가 함께 쓴다 —
  HANDOFF가 지적한 "두 구현이 따로 검증"하던 문제를 없앴다.
- **scheme**: http/https만 허용(file:// 등 차단).
- **포트**: 80/443만 허용 — "웹 페이지 크롤러" 범위를 벗어난 내부 서비스 포트 스캔(DB 5432,
  Redis 6379, 관리 콘솔 등)을 원천 차단. 필요해지면 설정 가능한 allowlist로 확장.
- **IP 판정**: 호스트명 패턴이 아니라 실제 DNS 해석 결과(`InetAddress`)로 판정. JDK 플래그
  (loopback/link-local/site-local/multicast/any-local)로 RFC1918·169.254.0.0/16(클라우드
  메타데이터 169.254.169.254 포함)·루프백·멀티캐스트를 막고, JDK가 놓치는 구간(0.0.0.0/8,
  100.64.0.0/10 CGNAT, 240.0.0.0/4 예약대역, IPv6 fc00::/7 unique-local — `isSiteLocalAddress()`는
  폐기된 `fec0::/10`만 인식)은 바이트 단위로 보충 검사했다.
- **리다이렉트**: Jsoup 자동 추적을 끄고 직접 순회(최대 5홉), 매 홉마다 scheme/포트/IP를
  다시 검증 — "1차 URL은 공인 IP인데 302로 내부망으로 리다이렉트"하는 우회를 막는다.
- **응답 크기**: `maxBodySize`를 명시(ETL 5MB, 챗 크롤러 2MB) — Jsoup 기본값(~2MB)에 암묵적으로
  기대는 대신 의도를 코드에 명시했다.
- **알려진 한계(문서화됨, 미해결)**: 검증 시점 DNS 해석 결과로 판단한 뒤 Jsoup이 같은
  호스트명으로 다시 연결하므로, 검증-연결 사이 DNS가 바뀌는 진짜 DNS-rebinding TOCTOU까지는
  막지 못한다. 완전 차단하려면 검증에 쓴 IP로 직접 연결(주소 pinning)해야 하는데 Jsoup 커넥션
  계층을 우회해야 해서 별도 HTTP 클라이언트가 필요하다. 로컬 단일 사용자 앱이라는 위협
  모델상 우선순위를 낮췄다 — 인터넷 노출·다중 사용자 신뢰 경계 확대 시 먼저 강화해야 할 항목.
- **검증**: `SafeUrlFetcherTest`가 scheme/포트 거부, IP 차단 매트릭스(JDK 플래그+보충 검사
  전부), 소켓 연결 전 fail-closed, 리다이렉트 URL 재구성을 IP 리터럴만으로(실 DNS/네트워크
  없이) 검증한다. 실제 원격 서버 대상 리다이렉트 추적·성공 fetch·응답 크기 제한 자체는 CI에
  재현 가능한 네트워크가 없어 다루지 않았다 — 필요하면 로컬 HTTP 서버나 WireMock으로 후속
  검증 권장.
- **미커밋 PDF 실험과의 충돌 처리**: `EtlPipelineService.java`는 이번에도 PDF 실험으로
  미커밋 상태였다. 같은 `git stash` 분리 방식을 썼는데, 이번엔 import 블록이 서로 인접해
  `git stash pop`이 자동 병합에 실패하고 충돌 마커를 남겼다 — 두 import(`SafeUrlFetcher`,
  `LayoutAwarePdfDocumentReader`)를 수동으로 나란히 남기는 것으로 직접 해결했다. 병합 후
  전체 컴파일 재확인 완료.

### P1 #5 완료 기록 (GPU admission control — 채팅 생성)

**신규 파일**: [OllamaGenerationQueue.java](backend/src/main/java/com/bigteam/btllm/chat/service/OllamaGenerationQueue.java),
테스트 [OllamaGenerationQueueTest.java](backend/src/test/java/com/bigteam/btllm/chat/service/OllamaGenerationQueueTest.java),
[ChatWebSocketHandlerAdmissionTest.java](backend/src/test/java/com/bigteam/btllm/chat/controller/ChatWebSocketHandlerAdmissionTest.java).
**변경 파일**: [ChatWebSocketHandler.java](backend/src/main/java/com/bigteam/btllm/chat/controller/ChatWebSocketHandler.java),
`application.yaml`(`btllm.gpu.queue-capacity`), README(`BTLLM_GPU_QUEUE_CAPACITY` 문서화).

- **문제(HANDOFF 4-1)**: 메시지마다 독립 `.subscribe()`를 호출하고 반환값(Disposable)을
  버렸다 — 한 세션이 여러 요청을 동시에 시작할 수 있었고, 연결이 끊겨도 이미 시작된 Ollama
  호출이 백그라운드에서 GPU를 계속 점유했다. `QUEUED` 응답은 안내 문구일 뿐 서버가 순서를
  강제하지 않았다.
- **GPU 큐**: `OllamaGenerationQueue` — `ThreadPoolExecutor(core=max=1, bounded queue)`로
  Ollama 채팅 생성만 직렬화한다. 상용 provider(Claude/OpenAI/Gemini)는 이 큐를 거치지
  않는다 — 물리적으로 공유하는 자원이 아니라 로컬 GPU 제약으로 직렬화하면 손해만 본다.
  대기열 상한은 `BTLLM_GPU_QUEUE_CAPACITY`(기본 8) — 가득 차면 `RejectedExecutionException`
  → 사용자에게 "잠시 후 재시도" 오류.
- **직렬화 강제 방식**: Ollama 분기는 `.doOnNext(...).blockLast()`로 큐 워커 스레드를 실제로
  점유한다 — `.subscribe()`(비동기)로 넘기면 워커 스레드가 즉시 반환돼 동시성=1이 무의미해진다.
  토큰은 doOnNext에서 즉시 전송하므로 스트리밍 체감은 그대로다.
- **세션당 in-flight 1개**: 새 메시지가 오면 이전 in-flight를 취소하고 새로 시작한다.
  프론트(`ChatPage.tsx`)가 이미 스트리밍 중 입력을 막으므로 정상 단일 탭 사용에는 영향 없고,
  멀티탭·API 오남용에 대한 방어선이다.
- **취소**: `Future#cancel(true)`(Ollama, 큐 대기 중이면 실행 자체를 취소·실행 중이면 워커
  스레드 인터럽트 → Reactor `blockLast()`가 감지해 구독 취소) / `Disposable#dispose()`(상용
  provider) 둘 다 세션 하나의 취소 핸들로 통일 관리. 연결 종료(`afterConnectionClosed`)에서도
  호출 — 연결이 끊겨도 계속 돌던 문제를 닫는다.
- **세대(generation) 카운터**: "새 요청이 이전 걸 취소 → 새 취소 핸들 등록" 직후, 취소된
  이전 작업의 정리 코드가 뒤늦게 실행되며 방금 등록한 새 핸들을 지우는 레이스를 막기 위해
  둠. 종료 시점에 "내가 아직 최신 세대인지" 확인한 뒤에만 핸들을 지운다.
- **검증**: `OllamaGenerationQueueTest`(동시성 1 + 대기열 상한 계약 — 실행/대기순번/거부/대기중
  취소/실행중 취소 인터럽트, 순수 JDK 동시성 프리미티브만 사용), `ChatWebSocketHandlerAdmissionTest`
  (세대 증가·이전 취소·낡은 세대 정리 무해·cancelInFlight 예외 흡수·isCancellation 판정 —
  ChatClient fluent 체인 전체를 목으로 재현하는 대신 해당 로직만 package-private으로 열어
  직접 검증). `./gradlew test` 전체 통과(116개).
- **범위 밖(다음 항목)**: ETL의 Ollama 요약 호출(P1 #6)은 아직 이 큐를 안 거친다 — 채팅과
  ETL이 여전히 같은 GPU를 두고 경합할 수 있다. P1 #6 착수 시 "ETL 전용 별도 큐" vs "이 큐를
  공유"를 결정해야 한다 — 공유하면 진짜 GPU 전체 admission control이 되지만 ETL 응답성과
  채팅 응답성이 서로 영향을 주게 된다. provider/model 비용 한도(P1 #7), SSE 수명 관리(P1 #8)도
  아직 손대지 않음.
- **실측 안 함**: 동시 다중 세션 부하로 실제 큐잉·거부·취소 동작을 재현하는 부하테스트는
  이번 세션에서 하지 않았다(Ollama 미기동 환경) — `k6/chat-stream-test.js`로 다음 세션에
  수동 검증 권장.

---

## 최신 보수 진단 — 2026-08-27 Codex 세션 (현재 최우선 기준)

### 0. 요청과 작업 범위

사용자가 지시한 내용은 “다른 작업을 하지 말고, 지금까지 진행한 내용을 쭉 리뷰하면서
성능 취약점·보안 취약점·안정성·유지보수 위험을 먼저 진단”하는 것이었다.

이번 세션에서 수행한 작업:

- 현재 작업 트리와 `handoff.md`, README, `docs/`, 백엔드·프론트엔드·Docker·모니터링 설정 검토
- 인증/인가, REST, WebSocket, SSE, RAG 수집·검색·삭제, URL fetch, 모델 선택, 배포 경계 추적
- 독립 보안 기준선 감사와 아키텍처/위협 모델 검토 후 부모 세션에서 source-to-sink 재검증
- Codex Security 정적 스캔 완료
  - scan ID: `6e7b7b2a-01ef-4038-8d8b-0e8e563d3b4d`
  - 결과: **Critical 1, High 5, Medium 2**
- 성능·안정성·유지보수 병목 별도 검토

하지 않은 작업:

- 취약점 수정, 설정 변경, 데이터 삭제, 커밋·푸시
- 실제 공격 PoC 또는 동적 침투 테스트
- 인터넷 기반 최신 의존성 CVE/SCA 조회
- 미커밋 PDF 파서 작업의 수정·정리·되돌리기

따라서 아래 결과는 **현재 소스와 설정의 정적 진단**이다. 실제 외부 노출도는 호스트 방화벽,
TLS 종단, 리버스 프록시, 환경변수 주입 상태에 따라 달라질 수 있다.

### 1. 위협 모델과 핵심 결론

이 시스템은 이름과 달리 사실상 “완전한 단일 사용자 로컬 앱”만은 아니다.

- 공개 회원가입과 JWT 인증이 있다.
- 채팅방은 사용자 소유권 검사를 한다.
- 반면 pgVector 지식베이스는 전역 저장소이며 사용자/tenant 메타데이터가 없다.
- 로그인 사용자는 이름이 `/admin/etl`인 API를 모두 호출할 수 있다.
- 사용자가 URL, 파일, provider/model을 제어해 서버 네트워크·파서·GPU·외부 API 비용에 영향을 준다.
- Compose는 DB와 관측 서비스를 호스트 포트에 공개한다.

따라서 현재 가장 큰 위험은 모델 자체보다 **모델을 둘러싼 운영 경계**다. 제한된 GPU 환경에서는
권한·쿼터·큐가 없는 것이 보안 문제인 동시에 곧바로 성능 장애가 된다.

보호해야 할 핵심 자산:

- JWT 서명 권한과 사용자 신원
- 사용자별 채팅방·대화 이력·Spring AI chat memory
- 업로드 문서, URL, 요약, 임베딩, pgVector 메타데이터
- Ollama GPU 시간과 외부 provider API 키·과금 한도
- PostgreSQL 데이터, 애플리케이션 로그, Loki/Prometheus/Grafana 정보

### 2. 검증된 보안 취약점

#### 2-1. Critical — Compose 기본 JWT 키로 임의 토큰 위조 가능

**경로**:

`docker-compose.yml:38`의 저장소 공개 fallback
→ `JwtProvider.java:20-24`가 그대로 HMAC 키로 사용
→ `JwtAuthFilter.java:28-40`이 서명된 subject를 사용자 조회 없이 `ROLE_USER`로 신뢰

**전제 조건**: `JWT_SECRET`을 명시하지 않은 Compose 백엔드가 공격자에게 도달 가능해야 한다.

**영향**:

- 순차적인 사용자 ID를 subject로 넣어 타 사용자 신원 위조 가능
- 타 사용자 채팅방/이력 접근 시도
- 전역 ETL·RAG 조작
- 설정된 상용 provider 호출과 자원·비용 소비

**반증/완화 요소**:

- Compose가 아닌 직접 실행에서는 `application.yaml`의 랜덤 fallback이 사용된다.
- 운영자가 충분한 `JWT_SECRET`을 주입하면 공개 fallback은 덮어써진다.
- 하지만 문서화된 Compose 기본 경로 자체는 안전 실패가 아니라 알려진 키로 기동한다.

**수정 방향**:

- Compose fallback 삭제, 미설정·저엔트로피 키면 애플리케이션 시작 실패
- 기존 키 회전 및 발급 토큰 무효화
- JWT subject를 활성 사용자 DB 레코드와 대조
- 직접 실행/Compose/CI 각각의 시작 실패·성공 테스트 추가

#### 2-2. High — 일반 가입자가 전역 관리자 ETL과 지식베이스를 조작

**경로**:

공개 signup
→ 모든 JWT 사용자에게 `ROLE_USER`
→ `SecurityConfig.java:54-64`의 `anyRequest().authenticated()`
→ `EtlController.java:38-144`의 URL/PDF/파일 업로드, source 목록·삭제
→ `EtlSourceService.java:21-50`의 전역 `vector_store` 조회·삭제

`LlmTools.java:120-132`의 지식베이스 검색에도 사용자 필터가 없다.

**영향**:

- 다른 사용자의 문서명·URL 열거 및 문서 내용 검색
- 임의 source의 모든 청크 삭제
- 악성/오염 문서를 넣어 다른 사용자 RAG 답변 변조
- 비싼 요약·임베딩·GPU 작업을 반복 호출

**반증**: 채팅방 REST와 WebSocket은 사용자 소유권을 검사한다. 즉 다중 사용자 경계가 이미 존재하지만,
RAG에만 동일한 경계가 빠져 있다.

**결정이 필요한 설계**:

- 정말 관리자만 문서를 관리한다면 `/api/v1/admin/**`에 `ROLE_ADMIN` 강제
- 일반 사용자가 문서를 올리는 제품이라면 vector metadata에 `owner_id`/`tenant_id`를 넣고
  ingest/search/list/delete/progress 전 경로에서 동일 predicate 강제
- 둘을 섞지 말 것. “공유 지식베이스”라면 공유 범위와 관리자 권한을 명시해야 한다.

#### 2-3. High — ETL과 LLM 크롤러의 SSRF

**경로 A**: `EtlUrlRequest.java:8-10`의 `@NotBlank` URL
→ `EtlPipelineService.java:56-74`의 `Jsoup.connect(url)`

**경로 B**: 사용자 프롬프트가 모델 tool argument에 영향
→ `LlmTools.java:50-74`의 `Jsoup.connect(url)`
→ 결과 본문이 모델과 사용자에게 반환

**빠진 통제**:

- HTTP/HTTPS scheme 허용목록
- loopback/private/link-local/reserved/cloud metadata IP 차단
- DNS rebinding 고려와 resolved IP 검사
- 포트 제한
- redirect 목적지 재검증
- ETL 응답 크기/다운로드 예산

10/15초 timeout과 chat tool의 3,000자 절단은 자원 일부만 제한할 뿐 내부망 접근을 막지 않는다.

**수정 방향**: 두 구현이 따로 검증하지 않도록 공통 `SafeUrlFetcher` 계층을 만들고, DNS 해석 결과와
모든 redirect를 검사한다. 가능하면 별도 egress proxy/격리 프로세스로 보낸다.

#### 2-4. High — 비제한 비동기 ETL로 heap·CPU·GPU 고갈

**경로**:

`application.yaml:124-128` 최대 50MB 파일
→ `EtlController.java:59-83`의 `MultipartFile.getBytes()` 전체 복사
→ `@Async` ETL
→ PDFBox/Tika 파싱, splitter, Ollama 요약, bge-m3 임베딩, pgVector 저장

**확인된 공백**:

- 전용 bounded executor/queue/rejection policy 없음
- 사용자별·전역 동시 작업 수 없음
- 파서 page/object/text/decompression 예산 없음
- 최대 청크·최대 색인 크기 없음
- 처리 시간 제한·취소·중복 문서 방지 없음

파일당 50MB 제한은 동시에 여러 작업이 대기할 때의 합산 heap과 압축/구조 복잡도 증폭을 막지 못한다.
또한 ETL 요약과 채팅이 같은 Ollama/GPU를 사용하므로 문서 업로드가 채팅 응답을 마비시킬 수 있다.

**수정 방향**:

- 업로드를 bounded 임시 저장소로 스트리밍
- ETL 전용 작은 executor와 명시적 queue/rejection/backpressure
- 사용자별 1개, 전역 N개 등 하드웨어 기준 admission control
- 파싱/텍스트/페이지/청크/시간 제한과 cancellation
- 채팅 우선순위 또는 ETL/채팅 Ollama 작업 스케줄러 통합

미커밋 `LayoutAwarePdfDocumentReader`는 PDF 증폭 표면에 추가되지만, 근본 문제인 eager byte copy와
비제한 async 파이프라인은 기존 코드 문제다. 둘을 구분해서 수정한다.

#### 2-5. High — client-controlled provider/model로 비용 남용·캐시 증가

**경로**:

WebSocket query의 `provider`, `model`
→ `ChatWebSocketHandler.java:70-79`, `127-148`
→ `ChatClientFactory.java:181-183`의 `provider:model` 캐시 키
→ configured commercial provider 옵션으로 그대로 전달

`ModelController`가 UI에 정적 목록을 보여주지만 서버는 그 목록을 allowlist로 강제하지 않는다.
각기 다른 model 문자열은 `ConcurrentHashMap`에 계속 남고, 유효 provider라면 서버 소유 API 키로
호출될 수 있다. 사용자별 동시성·토큰·비용 한도도 없다.

**수정 방향**:

- 서버 단일 provider/model registry를 만들고 UI 목록과 검증이 같은 소스를 사용
- 캐시 크기 상한 또는 고정 등록 모델만 캐시
- 사용자별 동시 실행, 일/분 토큰, 외부 비용 한도와 circuit breaker
- 고비용 모델의 별도 권한

#### 2-6. High — Docker 데이터/관측 서비스 기본 노출

`docker-compose.yml`이 PostgreSQL 5433, Prometheus 9090, Grafana 3000, Loki 3100을 짧은 형식으로
호스트에 publish한다. 이 형식은 기본적으로 loopback 전용이 아니다.

동시에 다음 기본값이 존재한다.

- PostgreSQL 저장소 공개 고정 계정
- Grafana `admin/admin`
- Loki `auth_enabled: false`
- Prometheus lifecycle과 remote-write receiver 활성화

**전제 조건**: 호스트/VM/사내망에서 해당 포트가 도달 가능해야 한다. 방화벽이 차단할 수 있지만
Compose 자체는 이를 보장하지 않는다.

**수정 방향**:

- 개발 포트는 `127.0.0.1:host:container`로 바인딩하거나 host publish 제거
- secret 외부 주입, Grafana 최초 비밀번호 강제
- 불필요한 lifecycle/remote-write 비활성화
- 내부 Docker network와 운영용 관측 인증 분리

#### 2-7. Medium — WebSocket URL에 JWT 노출, `ws://` 고정

`frontend/src/hooks/useWebSocket.ts:78-86`에서 localStorage JWT를 query string에 넣고
페이지 protocol과 무관하게 `ws://`를 사용한다.

**영향**:

- HTTP/LAN 환경에서 bearer token 평문 노출
- 프록시·관측·오류 telemetry에 URL이 남으면 1시간 토큰 유출
- HTTPS 페이지에서는 mixed-content로 채팅 연결 실패

**수정 방향**: HTTPS에서는 WSS를 사용하고, query bearer 대신 짧은 수명의 일회용 WS ticket 또는
Secure/SameSite 쿠키와 strict Origin 검사를 적용한다. `WebSocketConfig`의 `*` Origin도 함께 제한한다.

#### 2-8. Medium — 공개 SSE가 연결마다 raw OS thread 생성

`SecurityConfig.java:57-59`에서 progress endpoint가 permitAll이고,
`EtlController.java:89-128`은 연결마다 `new Thread(...).start()`를 수행한다.

유효 job ID를 알면 최대 10분 동안 다수 스레드를 유지할 수 있다. 무효 UUID는 첫 응답 후 종료하므로
위험을 낮추지만, 요청마다 unmanaged thread가 만들어지는 구조 자체는 남는다.

**수정 방향**:

- 인증 가능한 fetch stream 또는 짧은 수명의 signed job ticket
- job에 owner 저장·검증
- bounded scheduler/reactive publisher
- 사용자/IP/job별 subscriber 제한과 rate limit

### 3. 보안 검토에서 문제 없음으로 확인한 경로

다음 항목은 의심했지만 현재 코드에서 직접 취약점으로 확정하지 않았다.

- 일반 채팅방 REST 조회·삭제는 사용자 소유권을 검사한다.
- WebSocket 메시지도 `conversationId`가 JWT 사용자의 방인지 확인한다.
- source 삭제 SQL은 문자열 연결이 아니라 `JdbcTemplate` bind parameter를 사용한다.
- Markdown renderer는 raw HTML을 건너뛰고 링크에 `noopener noreferrer`를 적용한다.
- `application.yaml`의 Spring 기본 `admin/admin` 사용자는 HTTP Basic/form login이 비활성이라 현재
  직접 인증 우회 경로는 아니다. 다만 죽은 설정이므로 제거하는 편이 낫다.
- BCrypt 비밀번호 저장과 로그인 실패 메시지 통일은 적절하다.

### 4. 성능·안정성 진단

#### 4-1. 실제 1순위 병목은 GPU 단일 서빙 슬롯

기존 부하실험에서 VRAM을 확보해도 동시 2~3명이면 대부분 30초를 넘었다.
`QUEUED` 응답은 사용자 안내일 뿐 서버 queue·순번·용량 제한을 구현하지 않는다.

현재 WebSocket은 메시지마다 독립 `.subscribe()`를 시작하고 Disposable을 보관하지 않는다
(`ChatWebSocketHandler.java:150-193`). 따라서:

- 한 사용자가 여러 요청을 동시에 시작 가능
- 연결이 닫혀도 Ollama/API 작업이 계속될 수 있음
- 같은 RTX 4060 Ti 단일 슬롯에서 ETL 요약·채팅·임베딩이 경합
- 외부 provider 사용 시 불필요한 과금 지속 가능

**보수 방향**: 세션/사용자별 in-flight 1개, 전역 GPU queue, queue 상한과 거부, 순번/예상 대기 표시,
disconnect·새 요청 시 cancellation을 하나의 admission-control 계층으로 구현한다.

#### 4-2. 스트리밍 응답 누적이 O(n²)

`TokenTrackingAdvisor.java:64,82`는 `AtomicReference<String>`에 매 chunk마다
`existing + chunk`를 수행한다. 응답이 길수록 전체 문자열을 반복 복사해 GC와 지연을 늘린다.

**보수 방향**: Flux 직렬 실행 보장을 확인한 뒤 `StringBuilder`/buffer를 사용하거나, 필요하면
동기화된 builder/collector로 한 번만 문자열을 만든다. 토큰 메타데이터와 저장 실패 처리도 테스트한다.

#### 4-3. 채팅 이력 검색과 조회가 무제한

- `ChatHistoryRepository.java:22-23`: `LOWER(content) LIKE '%keyword%'`라 일반 인덱스를 못 쓰고
  모든 일치 row를 읽는다.
- `LlmTools.java:96-104`: DB에서 전체 결과를 받은 뒤 Java에서 5개만 자른다.
- `ChatRoomService.java:77-88`: 방의 전체 대화 이력을 pagination 없이 반환한다.

**보수 방향**: DB 단계 `Pageable`/LIMIT, 최신 또는 관련도 순서 정의, PostgreSQL trigram/FTS 검토,
REST cursor pagination과 프론트 점진 로딩을 적용한다.

#### 4-4. ETL progress 메모리 누수

`EtlProgressTracker`는 완료/실패 상태를 map에 넣고, SSE가 완료를 관찰할 때만 `remove()`한다.
클라이언트가 progress SSE를 열지 않거나 중간에 끊기면 완료 job이 계속 남을 수 있다.

**보수 방향**: 생성/완료 시각 저장, TTL cache와 주기 청소, 최대 job 수, 완료 후 grace period를 둔다.
다중 인스턴스를 고려한다면 DB/Redis 등 외부 상태 저장 여부도 결정한다.

#### 4-5. 프론트 WebSocket 전송 큐 무제한

`frontend/src/hooks/useWebSocket.ts:54,137-143`의 `outboundQueueRef`는 연결 실패 중 메시지를 계속 쌓는다.
최대 길이, 중복 방지, conversation/model 전환 시 폐기 정책이 없다.

**보수 방향**: UI에서 in-flight/연결 중 전송을 제한하고, 큐 길이 1 또는 작은 고정값, 방·모델 변경 시
명시적 폐기, 재전송 idempotency key를 둔다.

#### 4-6. 운영 튜닝이 실제 병목과 맞지 않음

`application.yaml`은 Tomcat thread 400, Hikari pool 30으로 늘렸지만 GPU 슬롯은 1개다.
HTTP/DB 동시성만 키우면 모델 앞 대기와 메모리 점유를 더 키울 수 있다. GPU admission control을 먼저
만들고 그 queue 처리량에 맞춰 Tomcat/Hikari를 재측정해야 한다.

### 5. 유지보수 위험

#### 5-1. 개발·운영 설정 혼합

현재 한 설정에 다음이 함께 존재한다.

- `spring.jpa.hibernate.ddl-auto: update`
- `show-sql: true`
- Hibernate SQL DEBUG
- Spring AI schema `initialize-schema: always/true`
- Actuator health `show-details: always`
- 개발용 DB 계정과 관측 서비스 기본 계정

**보수 방향**: `application.yaml` 공통 최소값 + `application-local.yaml` + `application-prod.yaml` 분리,
Flyway/Liquibase 기반 명시적 migration, 운영 시작 시 secret·profile 검증을 적용한다.

#### 5-2. Spring AI 내부 테이블 직접 의존

`EtlSourceService`가 Spring AI 기본 `vector_store`의 metadata JSON 구조에 직접 SQL을 실행한다.
라이브러리 업그레이드나 스키마 변경에 취약하다. repository adapter로 격리하고 integration test와
migration 소유권을 명확히 한다.

#### 5-3. 문서와 실험 결과의 시점 불일치

`docs/rag-accuracy-experiment.md`에는 “실험 당시 topK=5”와 “현재 운영 topK=3”가 함께 있고,
일부 줄은 topK=5를 현재 운영값처럼 표현한다. `handoff.md`의 과거 “작업 트리 깨끗” 기록도 현재와
달랐다. 생성 보고서에 실행 commit/config/data digest를 기록해 오래된 결과를 명확히 구분해야 한다.

#### 5-4. 채팅방 삭제 후 Spring AI memory 잔존 가능성

`ChatRoomService`는 커스텀 채팅방/이력은 삭제하지만 별도 Spring AI JDBC chat memory를 함께
삭제하지 않는다. conversation UUID 재사용 가능성은 낮지만 개인정보 삭제·보존 정책 관점에서
잔존 데이터가 된다. 삭제 동작과 retention 정책을 통합해야 한다.

### 6. 미커밋 PDF 파서 작업 — 반드시 별도 보존

현재 작업 트리에 다음 계열의 미커밋 작업이 있다.

- `backend/src/main/java/.../rag/reader/LayoutAwarePdfDocumentReader.java` 신규
- `EtlPipelineService`의 PDF reader 교체
- PDF layout reader/diagnostic/golden-set 테스트
- 평가 항목 `evidenceType` 분류와 보고서 확장
- `backend/build.gradle` 진단 task 추가
- `tmp/` PDF 페이지 이미지

현재 reader는 vector ruling을 휴리스틱으로 세어 표 페이지를 판정하고, 표로 판단된 페이지에 대해
원문 전체와 좌/우 반쪽 문서를 함께 만든다. 직전 로컬 재색인에서 청크가 **64 → 83**으로 증가했지만
`track-1`, `registration-1`, `prize-1`의 topK=3 필수 사실 누락은 남았다.

따라서 현 상태는:

- 표 구조 해결이 아직 검증되지 않음
- 임베딩·저장·검색 후보 수는 증가
- 전체 페이지와 반쪽 문서의 중복으로 컨텍스트 오염 가능
- 표의 실제 bounding box가 아니라 페이지 좌우 절반이라 본문/헤더가 섞일 수 있음

**판정**: 폐기할 작업은 아니지만 현재 상태로 커밋하지 않는다. 보수작업과 섞지 말고 별도 branch/커밋으로
격리한 뒤 다음 회귀 기준을 모두 통과할 때만 채택한다.

1. TABLE 3문항의 retrieval 필수 사실 포함률
2. PROSE 5문항 비회귀
3. 전체 청크 수와 중복률
4. 색인 시간·embedding 호출량
5. generation 사실 포함률·평균/p95 지연 3회 반복

### 7. 구현 우선순위와 완료 조건

#### P0 — 외부 노출 전에 반드시 수정

| 순서 | 작업 | 최소 완료 조건 |
|---:|---|---|
| 1 | JWT secret 안전 실패 | 미설정 Compose 기동 실패, 충분한 키 기동 성공, 기존 토큰 무효화 문서화 |
| 2 | Docker 포트/기본 계정 잠금 | DB·관측 포트 loopback/internal, 기본 암호 제거, 불필요 control endpoint 비활성 |
| 3 | ETL/RAG 권한 경계 | 관리자 전용 또는 tenant 모델 하나를 결정하고 ingest/search/list/delete 테스트 |
| 4 | SSRF 공통 차단기 | loopback/private/link-local/redirect/DNS 재검증 테스트, 응답 크기 제한 |

#### P1 — 제한된 하드웨어에서 안정적으로 동작하기 위한 보수

| 순서 | 작업 | 최소 완료 조건 |
|---:|---|---|
| 5 | GPU admission control | 사용자별 in-flight 제한, 전역 bounded queue, queue full 거부, 취소 |
| 6 | ETL executor/파서 예산 | bounded executor, 업로드/파싱/청크/시간 limit, chat 우선순위 검증 |
| 7 | provider/model·비용 통제 | 서버 allowlist, cache 상한, 외부 provider 토큰·동시성 한도 |
| 8 | SSE/progress 수명 | raw thread 제거, owner 검증, TTL cleanup, subscriber 제한 |

#### P2 — 성능과 유지보수 부채

- 스트리밍 문자열 누적 선형화
- 대화 검색 DB LIMIT/FTS와 REST pagination
- WebSocket outbound queue 상한과 idempotency
- local/prod profile, migration, log/actuator 노출 정리
- Spring AI 내부 테이블 adapter와 integration test
- 채팅방 삭제/데이터 retention 통합
- 최신 dependency SCA와 동적 보안 테스트를 CI 또는 릴리스 절차에 추가

### 8. 보수작업 진행 원칙

- 한 번에 한 위험군만 수정하고 보안/성능/품질 회귀를 함께 측정한다.
- P0 수정 커밋에 PDF 파서 실험 파일을 섞지 않는다.
- 기존 사용자 변경을 자동 포맷·cleanup 명목으로 건드리지 않는다.
- 보안 테스트는 가능한 한 단위/통합 테스트로 재현하되 실제 내부망 스캔·과금 호출은 하지 않는다.
- GPU 보수는 “더 많은 동시성”이 아니라 **작은 bounded queue와 예측 가능한 거부/취소**가 목표다.
- 로컬 LLM 품질 실험은 보수 후 같은 골든셋·같은 GPU 상태·3회 반복으로 비교한다.
- 수정 전후 `git status`와 대상 diff를 확인하고, `feat/`·`fix/` 브랜치 규칙을 따른다.

---

## 최신 인수인계 — 2026-08-27 Claude 세션 (이 절이 현재 기준)

### 0. 사용자가 지시한 작업 방식 (반드시 유지)

- **모든 응답은 한국어로.**
- 기능 완성보다 **연구·학습 과정의 기록**을 중시한다. 기술 의사결정이 나온 경위를
  `docs/portfolio-improvement-log.md`에 `문제 → 리서치 → 설계 결정 → 구현 → 검증 → 남은 한계`
  순서로 누적한다. 포트폴리오 서술의 원재료가 되므로 "왜 그 선택을 했는가"를 반드시 남긴다.
- **세션이 바뀔 수 있으므로 이 HANDOFF.md를 항상 갱신한다.** 목적은 다음 세션의 AI가 작업 내용뿐
  아니라 **대화와 의사결정의 맥락**까지 이어받는 것이다.
- **적절한 타이밍마다 커밋·푸시**한다. 논리 구간 단위로 나눠 전략적으로 커밋한다.
- **README도 주기적으로 갱신**한다.
- 단발성 성공을 품질 개선으로 주장하지 않는다. 평가셋·반복 실행·정량 지표로 비교한다.
- Git 브랜치는 `feat/`, `fix/` 등 일반 형식. `codex/` 접두사 금지.

### 1. 이번 세션의 흐름 (의사결정 맥락)

사용자의 출발 질문은 **"이 프로그램은 사람의 어떤 문제를 해결하는가"** 였고, 합의된 답은:
*긴 문서(정부 공고문·계약서·매뉴얼)를 다 읽지 않고 자연어로 필요한 답만 얻되, 문서가 외부로
나가지 않고 API 비용도 들지 않는 로컬 RAG 비서.* 핵심 목표는
**"개인 PC에서 Ollama 로컬 모델이 정말 쓸 만한 수준이 되게 하는 것"**.

그래서 시행착오 대신 **먼저 웹 리서치**로 8GB급 GPU에서 로컬 LLM을 끌어올리는 방법론을 조사한 뒤,
근거 있는 순서로 하나씩 적용·측정하기로 했다. 진행 순서와 각 단계의 결론은 다음과 같다.

| 단계 | 조치 | 결과 |
|---|---|---|
| 1 | 사전 리서치(리랭킹, VRAM, 모델, tool calling) | 적용 후보 도출, 로그에 출처와 함께 기록 |
| 2 | 시스템 프롬프트 수정(BTLLM 정체성 + 조건부 `[출처]`) | 정체성 3/3 해결. `[출처]` 오적용은 **프롬프트만으로 한계** 확인 |
| 3 | 사용자 결정: 출처 가드레일은 **백로그**, 리랭킹으로 진행 | — |
| 4 | 하이브리드 재정렬(벡터+키워드) 도입 | 통과율 12.5%→25.0%. **랭킹 실패와 Recall 실패를 분리** |
| 5 | OpenAI provider 연동 + `.env` 키 주입 | 트러블슈팅 3건(임베딩 빈 충돌, TTS 오토컨피그, `.env` 바인딩) |
| 6 | 동일 골든셋 provider 비교 실험 | 실패를 검색/모델 문제로 분리 — **나중에 이 분류가 틀린 것으로 밝혀짐** |
| 7 | 포트폴리오 본문 작성 시작(`docs/portfolio.md`) | 4단 구조. 이후 작업마다 함께 갱신 중 |
| 8 | 요약 청크 색인(`DocumentSummarizer`) | **가설 실패.** 검색은 되나 본문 개요 청크와 중복. 대신 **컨텍스트 초과 발견** |
| 9 | 청크 크기 1500→800 | **최대 폭 개선.** 통과율 0%→50%, 사실 38.1%→76.2%, 지연 65.8s→50.6s |
| 10 | provider 재비교(청크 800에서) | 단발로 "동률" 나왔으나 **3회 반복으로 자가 반증** — 사실 포함률만 동등(77.8%) |
| 11 | 지연 진단 | 2번 틀린 뒤 원인 확정: **yaml `think:false`가 요청에 안 실림.** ON/OFF는 정면 트레이드오프 |
| 12 | 의도 기반 thinking 라우팅(`ThinkingRouter`) | 메커니즘 작동(단순 조회 23.7s)하나 **골든셋 7/8이 복합 질의라 효과 미측정** |
| 13 | 고착 실패 3문항 원인 규명 | **PDF 표 평탄화로 트랙-값 연결 소실** — 검색·생성이 아닌 **파싱 문제** |

**이 세션의 성격**: 개선 그 자체보다 **잘못된 진단을 스스로 반증한 기록**이 많다.
8·10·11·12단계가 모두 "가설이 틀렸음을 측정으로 확인한" 사례이며, 포트폴리오에서는 이것이
오히려 강한 소재로 쓰인다. 다음 세션도 **원하는 결론일수록 반복 측정으로 걸러야 한다.**

### 2. 가장 중요한 산출물 — 로컬이 상용과 동률, 남은 실패는 모델 무관

`./gradlew providerComparisonExperiment` **3회 반복** 실측
(청크 800 코퍼스·동일 8문항·동일 검색 파이프라인·모델만 교체, 각 24회 호출):

| Provider / 모델 | 통과율 (24회) | 사실 포함률 | 평균 지연 | p95 |
|---|---:|---:|---:|---:|
| ollama / qwen3:8b | 50.0% (12/24) | **77.8% (49/63)** | 88.9s | **188.6s** |
| openai / gpt-4o-mini | **58.3% (14/24)** | **77.8% (49/63)** | 4.9s | 7.1s |

회차별 편차: 로컬 50.0/50.0/50.0 (**0%p**), 상용 50.0/62.5/62.5 (12.5%p).
→ **로컬이 오히려 안정적이다.** 과거 로컬 편차(0%~62.5%)는 모델 비결정성이 아니라
그 사이 바뀐 파이프라인 설정 탓이었다.

**문항별 안정성:**

| 문항 | qwen3:8b | gpt-4o-mini | 의미 |
|---|---|---|---|
| round-general-1, eligibility-1, application-1 | ✅ 3/3 | ✅ 3/3 | 회귀 감시용 |
| round-local-1 | ⚠️ 2/3 | ✅ 3/3 | 간헐 |
| overview-1 | ⚠️ 1/3 | ⚠️ 2/3 | 간헐 — 단발로는 "성공"처럼 보였던 함정 |
| **track-1, registration-1, prize-1** | ❌ 0/3 | ❌ 0/3 | **12회 전부 실패 — 모델 무관, 파이프라인 문제 확정** |

→ 앞으로 검색·청킹 개선의 효과는 **이 실패 3문항으로 판정**한다.
`prize-1`은 **사실 6/6을 다 맞고도** 금지 오답에 걸려 실패하므로 성격이 다르다
(트랙별 수치 교차 귀속 또는 골든셋 판정 기준 재검토 대상).

> **주의**: 이전 단발 비교의 "62.5% 동률, 로컬 사실 81.0%"는 **우연이었고 폐기됐다.**
> 원하던 결론일수록 반복 측정으로 걸러야 한다.

> **주의**: 이전 HANDOFF의 분해표(로컬 25% vs GPT 62.5%, "모델 체급 문제" 분류)는
> 청크 1500 시절 값이라 **폐기됐다.** 두 모델이 동일하게 잘린 근거를 받고 있어서
> 비교로는 공통 결함이 드러나지 않았던 것이다. 파이프라인을 고치면 그 위에서 내렸던
> 비교 판단도 반드시 다시 검증할 것.

### 3. 바로 이어서 할 작업 (우선순위 순)

> **✅ 컨텍스트 초과 문제는 해결됐다.** 청크 1500 → 800으로 통과율 0% → 50%,
> 사실 포함률 38.1% → 76.2%, 지연 65.8s → 50.6s. 상세는 `docs/portfolio.md` 8장.
> **현재 기준선: 50.0% 통과 / 사실 76.2% / 평균 50.6s (청크 800, 63청크 코퍼스)**

1. **PDF 표 구조 보존 파싱 — 현재 최우선 (원인 규명 완료)**

   `track-1`·`registration-1`·`prize-1`이 두 모델 12회 시도 전부 실패한 **근본 원인을 찾았다.**

   `TrackAttributionDiagnostic`으로 검색 근거에 필수 사실이 있는지 확인한 결과:

   | 문항 | topK=3 | topK=4 | topK=5 | topK=8 |
   |---|---:|---:|---:|---:|
   | track-1 | 1/2 | 1/2 | 1/2 | 1/2 |
   | registration-1 | 1/3 | 1/3 | 1/3 | 1/3 |
   | prize-1 | **0/2** | 0/2 | 0/2 | 0/2 |

   **topK 상향은 답이 아니다.** 8까지 올려도 동일하고, 오히려 근거가 `num_ctx` 4096을 다시 넘는다.

   벡터 스토어 원문을 직접 열어보니 상금 정보는 **존재하지만 이렇게 저장돼 있다**:

   ```
   … 상금 MVP제작 육성프로그램 지원 육성프로그램 1:1멘토링 5억원 1:1멘토링 1억원 …
   ```

   `5억원`은 위치 1,290, `일반/기술`은 2,076으로 **786자 떨어져 있다.** 원문 PDF에서 두 트랙은
   **나란히 놓인 두 개의 표**였는데 `PagePdfDocumentReader`가 평탄화하며 열 구조가 사라졌다.
   **"일반/기술트랙 = 5억"이라는 정보가 색인 데이터에 애초에 없다.**

   → 검색 문제도 생성 문제도 아닌 **ETL(문서 파싱) 문제**다. 근거에 없는 사실은 어떤 모델도
   만들 수 없으므로, 상용 모델이 실패한 것도 정상이다(맞혔다면 그것이 환각).

   **다음 후보**:
   - (a) 표 구조 보존 파싱 — 좌표 기반 추출이나 표 인식 리더. 근본 해결이나 작업 규모가 크다.
   - (b) 알려진 제약으로 문서화 — 표가 많은 문서는 정확도가 떨어짐을 명시.
   - (c) 평가셋에서 표 기반 문항을 별도 범주로 분리해 파싱 개선 효과를 따로 측정.

   ⚠️ **이미 시도했고 실패한 것(반복 금지)**: topK 상향(3→8, 변화 없음).

2. **요약 청크 기능 재평가** — `DocumentSummarizer`는 동작하고 개요형 질의에서 1위로 검색되지만,
   현재 코퍼스에서는 본문 `1 사업 개요` 청크와 중복이라 점수를 올리지 못했다.
   기능은 유지 중이며, 청크 800 환경에서 켜고 끈 A/B를 아직 하지 않았다.

3. ~~provider 비교 반복 실행~~ — **완료.** 3회 반복 결과는 위 2절 참고.
   `PROVIDER_COMPARISON_REPETITIONS=3`으로 재현한다.

4. ~~의도 기반 thinking 라우팅~~ — **구현 완료(`ThinkingRouter`), 다만 효과 미입증.**

   단순 조회로 분류된 `application-1`은 thinking OFF로 가서 **23.7초에 품질 손실 없이 통과**했다
   (나머지 문항 89~146초). 메커니즘은 작동한다.

   그러나 **골든셋 8문항 중 7개가 복합 질의**라 총합 지표에 드러나지 않았다
   (통과율 50.0%로 동일, 평균 지연은 오히려 105.4초). 이 평가셋은 "문서 사실 정확도"를 재려고
   만든 것이라 라우팅이 갈릴 여지가 거의 없다.

   **다음에 할 것**: 단순 조회와 복합 추론이 섞인 **별도 평가셋**을 만들어 라우팅 효과를 측정한다.
   ⚠️ **기존 골든셋을 고쳐 쓰지 말 것** — 개선을 좋아 보이게 하려고 평가 기준을 바꾸는 셈이 된다.

   교훈: 개선안을 만들기 전에 **그 개선이 드러날 수 있는 측정 조건인지 먼저 확인**해야 한다.

5. **(참고) thinking 트레이드오프 원본 수치**

   지연의 진짜 원인은 규명 완료했다: `ChatClientFactory`가 `defaultOptions`를 통째로 지정해
   yaml의 `think: false`가 요청에 실리지 않아 **thinking이 켜진 채 동작**하고 있었다.
   보이지 않는 `<think>` 토큰이 지연의 대부분이었다(최종 답변은 100~450자에 불과).

   끄는 것이 답은 아니었다. 정면 트레이드오프다:

   | 지표 | thinking ON (기본값) | thinking OFF |
   |---|---:|---:|
   | 평균 / p95 지연 | 88.9s / 188.6s | **33.0s / 74.5s** |
   | 필수 사실 포함률 | **77.8%** | 57.1% |
   | 출처 표시율 | 100% | 100% |

   현재 `BTLLM_THINKING`(기본 `true`)으로 선택 가능하다. 다음은 **질의 성격에 따른 자동 선택**:
   단순 조회·문서 요약은 OFF, 트랙 비교·복합 추론은 ON. 판정은 같은 골든셋으로 한다.

6. **`num_predict` 절단은 이미 시도했고 실패했다(반복 금지).** 512로 낮추면
   `[출처]`가 잘려 출처 표시율 100% → 37.5%로 붕괴하고 지연은 8.4초만 줄어든다.
   현재 기본값 2048은 폭주 방지용 안전장치이지 최적화 수단이 아니다.
7. **Qwen3 8B 생성 파라미터 A/B** — temperature 0.3 / 0.5 / 0.7 비교.
   판정은 위 1번의 실패 3문항으로 한다.
8. **Qwen3.5 후보 비교** — `qwen3.5:4b-q8_0`(약 5.3GB) 우선. `qwen3.5:9b`는 6.6GB라
   bge-m3·KV cache까지 더하면 CPU offload 위험. **Ollama의 Qwen3.5 지원이 아직 불안정하다는
   보고가 있어** 저위험 후보부터 시도한다.
9. **백로그**: `[출처]` 조건부 표시 코드 가드레일, cross-encoder 리랭커(CPU 서빙),
   Google provider 키 부재 시 기동 실패 근본 수정, GPU 서빙 슬롯 1개 구조의 대기열·백프레셔,
   LLM 운영 메트릭(Micrometer), 평가 문서 2~3종으로 확대.

### 4. 이번 세션에서 반드시 알아야 할 함정

- **`.env` 바인딩**: Spring의 `SPRING_AI_..._API_KEY` 대문자 완화 바인딩은 **OS 환경변수에만**
  적용된다. `.env`(properties) 파일로 주입하려면 yaml에 `api-key: ${SPRING_AI_OPENAI_API_KEY:}`
  플레이스홀더가 **반드시** 있어야 한다. 없으면 기동은 성공하는데 키만 비어 있다.
- **멀티 provider 임베딩 충돌**: 스타터를 추가할 때 임베딩 오토컨피그가 `matchIfMissing=true`로
  겹치면 `EmbeddingModel` 빈이 2개가 되어 기동이 죽는다. `spring.ai.model.embedding: ollama`로
  고정해둔 상태다.
- **Google provider**: 키가 비어 있으면 `CachedContentService`가 `ChatClientFactory`의 try-catch
  밖에서 실패해 앱이 죽는다. Gemini를 안 쓰면 `GOOGLE_AI_API_KEY=dummy`를 넣어야 한다.
  실험 gradle 태스크들은 이미 더미를 주입하고 있다.
- **`providerComparisonExperiment`는 실제 과금**이 발생한다. 일반 `test`에서는 태그로 제외돼 있다.
- **`defaultOptions`가 yaml을 덮어쓴다**: `ChatClientFactory`에서 `defaultOptions`를 통째로
  지정하므로 `application.yaml`의 `ollama.chat.options.*`는 **요청에 실리지 않는다.**
  `think`가 이 때문에 무시되고 있었다. 옵션을 추가할 때 반드시 팩토리 쪽에도 넣을 것.
- **실험은 `chat_histories`에 남지 않는다**: 임시 conversationId를 쓰므로 DB의 토큰 기록은
  실험 결과가 아니다. 그 데이터로 실험을 진단하면 틀린다(실제로 한 번 틀렸다).
- **색인 파라미터 변경 시 재색인 필수**: 청크 크기·요약 청크는 색인 시점 값이라
  `reindexDocument` 없이는 반영되지 않는다.
- **평가 코퍼스가 바뀌면 기존 기준선과 비교 금지**: 과거 21청크·31,096자 → 현재
  63청크(+요약 1)·청크 800이다. 코퍼스가 다르면 반드시 기준선을 다시 재고 비교할 것.
- 나머지 하드웨어 주의사항(VRAM, `keep-alive: -1s`)은 아래 기존 문서 절을 그대로 따른다.

### 5. 실행 명령

```powershell
cd C:\Users\User\claude-project\personal-llm-remaster\backend
.\gradlew.bat test                              # 일반 테스트 52건 (실험 제외)
.\gradlew.bat ragGenerationQualityExperiment    # PDF 답변 품질 (약 7~10분)
.\gradlew.bat localConversationQualityExperiment
.\gradlew.bat providerComparisonExperiment      # 로컬 vs 상용 (과금 주의)
```

진단 도구 (LLM 호출 없이 검색 단계만 관측 — 수십 초):

```powershell
.\gradlew.bat trackAttributionDiagnostic   # 실패 3문항의 근거 존재 여부 + topK별 비교
.\gradlew.bat summaryRetrievalDiagnostic   # 요약 청크가 개요형 질의에 잡히는지
```

색인 로직을 바꾼 뒤에는 **반드시 재색인**해야 평가가 유효하다.

```powershell
$env:REINDEX_PDF_PATH = "C:\Users\User\Desktop\DJP-2026\창업\(제2026-511호)「모두의_창업_프로젝트」_통합_모집_공고_(2차).pdf"
.\gradlew.bat reindexDocument
```

실험 파라미터 주입 예시:

```powershell
$env:PROVIDER_COMPARISON_TARGETS = "ollama=qwen3:8b,openai=gpt-4o"
$env:PROVIDER_COMPARISON_REPETITIONS = "3"
$env:BTLLM_RAG_CHUNK_SIZE = "800"    # 변경 시 재색인 필요
$env:BTLLM_THINKING = "false"        # 지연 우선(품질 하락 감수)
```

### 6. 보고서 위치

| 파일 | 내용 |
|---|---|
| **`docs/portfolio.md`** | **채용 담당자가 읽는 포트폴리오 본문.** 4단 구조. 작업마다 함께 갱신할 것 |
| `docs/portfolio-improvement-log.md` | **의사결정 기록의 본체.** 매 작업마다 누적 |
| `docs/provider-comparison-experiment.md` | 로컬 vs 상용 비교 (3회 반복 지원) |
| `docs/rag-generation-quality-experiment.md` | PDF 답변 품질 (문항별 thinking 라우팅 판정 포함) |
| `docs/local-conversation-quality-experiment.md` | 대화 품질 |
| `docs/rag-accuracy-experiment.md` | 청크 크기별 Recall |
| `docs/performance-ux-improvement-plan.md` | 성능·UX 이슈 9건 진단 |

보고서는 **매 실행마다 덮어쓴다.** 비교 결과를 보존하려면 실행 전후로 따로 복사해야 한다.

---

## 이전 인수인계 — 2026-08-26~27 Codex 세션 (역사 기록)

> 위의 2026-08-27 Claude 세션 절이 현재 기준이다. 이 절과 그 아래 기록은 문제 발견 과정과 과거
> 측정값을 보존한 역사 문서이며, `아직 push 안 됨`, `QUEUED 미구현`, `RAG 수정 미적용` 등의
> 항목은 이미 해결돼 현재 상태와 다를 수 있다.

### 1. 사용자가 합의한 작업 방식

- 포트폴리오 프로젝트이므로 기능만 완성하지 않고 `문제 → 발견 → 진단 → 해결 → 검증`을 기록한다.
- 작업을 논리 구간으로 나누고 각 구간마다 전략적으로 커밋·푸시한다.
- 단발성 성공을 품질 개선으로 주장하지 않고 평가셋·반복 실행·정량 지표로 비교한다.
- 사용자 응답과 실제 채팅 UI의 Markdown을 일반 상용 LLM처럼 읽기 쉽게 유지한다.
- Git 브랜치가 필요할 때는 `feat/`, `fix/` 등 일반 형식을 사용하고 `codex/` 접두사는 쓰지 않는다.

### 2. 이 세션에서 완료하고 GitHub에 푸시한 커밋

현재 원격 `origin/main`과 로컬 `main`은 기능·실험 커밋 `c48e985`까지 일치한다.

| 커밋 | 핵심 작업 | 검증·의미 |
|---|---|---|
| `9c7c14e` | 백엔드·프론트 품질 게이트 복구 | 테스트·lint·build를 다음 변경의 기준선으로 복원 |
| `8119362` | RAG 검색 근거 구조화와 실제 파일명 출처 계약 | 관련도 순위·청크 위치·출처를 모델에 전달 |
| `51a7e44` | RAG topK 5→3, num_ctx 4096 명시 | 검색 문맥 7,540자→4,616자, 38.8% 감소 |
| `a6f2d42` | PDF 기반 생성 품질 평가셋 | 8문항 실제 qwen3:8b 반복 기준선과 실패 복구형 보고서 |
| `9757dc1` | 로컬 모델 대기 UX와 Markdown 스트리밍 무결성 | READY/QUEUED/TOKEN 분리, 경과 시간, 표·코드 렌더링 |
| `c48e985` | 로컬 모델 대화 품질 기준선 | 정체성·기억·주제 전환·출력 계약 평가 하네스와 Qwen3 8B 실측 |

세부 진단 기록은 `docs/portfolio-improvement-log.md`에 누적했다.

### 3. 주요 완료 결과

#### RAG 구조·검색 개선

- `searchKnowledgeBase` 결과를 관련도 순서, 실제 출처, 문서 내 청크 위치와 함께 구조화했다.
- 작은 모델이 4~5위 부록을 핵심처럼 답하던 문제를 확인해 topK를 3으로 축소했다.
- “모두의 창업 프로젝트” 개요 질문에서 사업 목적·트랙·선정 규모·지원 내용 관련성을 회복했다.
- 다만 트랙별 사업자등록 시점·상금·세부 수치 귀속은 여전히 불안정하다.

#### PDF 생성 품질 기준선

`./gradlew ragGenerationQualityExperiment` 실제 실행 결과:

| 지표 | 자동 엄격 기준 |
|---|---:|
| 문항 통과율 | 12.5% (1/8) |
| 필수 사실 포함률 | 28.6% (6/21) |
| 출처 표시율 | 100% (8/8) |
| 평균 응답 시간 | 60.4초 |
| p95 응답 시간 | 87.1초 |

사람 검토 보정은 통과율 37.5%(3/8), 사실 포함률 38.1%(8/21)다. 자동 지표는 반복 비교용으로
엄격하게 유지한다. 상세 결과는 `docs/rag-generation-quality-experiment.md` 참고.

#### 대기 UX와 Markdown

- WebSocket 연결용 빈 `TOKEN`을 없애고 `READY`, 요청 접수 `QUEUED`, 내용 `TOKEN`을 분리했다.
- 접수 안내·경과 시간·10초 이후 로컬 모델 지연 안내를 추가했다.
- 제목·목록·표·코드 블록·코드 복사 UI를 답변 전용 `MarkdownMessage`로 분리했다.
- 브라우저 E2E에서 실시간 답변만 Markdown 개행이 사라지는 현상을 발견했다.
- 원인은 `text.isBlank()`가 Ollama의 개행 전용 스트리밍 청크를 버리던 것이며, `isEmpty()`로 좁혀 해결했다.
- 실제 qwen3:8b 요청에서 QUEUED 표시, 표·코드 렌더링, `복사됨`, 새로고침 후 동일 구조를 확인했다.
- 브라우저 QA 과정에서 로컬 DB에 임시 `Markdown UI QA` 채팅방과 `codex-ui-*` 테스트 계정이 생겼다.
  런타임 생성 비밀번호는 저장하지 않았으며 제품 데이터가 아니므로 필요하면 나중에 정리해도 된다.

### 4. 마지막 완료 작업 — 로컬 모델 대화 품질 평가 하네스

아래 파일은 `c48e985 test(llm): add conversational quality baseline`으로 커밋·푸시했다.

| 상태 | 파일 | 내용 |
|---|---|---|
| 수정 | `backend/build.gradle` | `localConversationQualityExperiment` 태스크와 일반 테스트 태그 격리 |
| 신규 | `backend/src/test/java/com/bigteam/btllm/chat/experiment/ConversationAnswerEvaluator.java` | 필수 의미·금지 응답 정규식 판정기 |
| 신규 | `backend/src/test/java/com/bigteam/btllm/chat/experiment/ConversationAnswerEvaluatorTest.java` | 판정기 단위 테스트 2건 |
| 신규 | `backend/src/test/java/com/bigteam/btllm/chat/experiment/LocalConversationQualityExperiment.java` | 모델·생성 파라미터별 실제 Ollama 반복 평가 |
| 신규 | `backend/src/test/resources/conversation-eval/golden-set.json` | 정체성·기억·주제 전환·출력 계약 4개 시나리오 |
| 신규 | `docs/local-conversation-quality-experiment.md` | 최신 기준선 자동 보고서 |
| 수정 | `docs/portfolio-improvement-log.md` | 평가 설계·기준선·발견 내용 기록 |

실험기는 다음 환경변수를 지원한다.

```text
LOCAL_MODEL_EVAL_MODEL
LOCAL_MODEL_EVAL_TEMPERATURE
LOCAL_MODEL_EVAL_TOP_P
LOCAL_MODEL_EVAL_TOP_K
LOCAL_MODEL_EVAL_THINKING
LOCAL_MODEL_EVAL_REPETITIONS
LOCAL_MODEL_EVAL_NUM_CTX
LOCAL_MODEL_EVAL_NUM_PREDICT
```

마지막 실행은 정상 완료됐으며 최신 보고서 시각은 `2026-08-27T00:00:24`다.

| 기준선 설정 | 값 |
|---|---|
| 모델 | `qwen3:8b` |
| 생성 | temperature 0.3, top_p 0.8, top_k 20, thinking false |
| 컨텍스트·출력 | num_ctx 4096, num_predict 768 |
| 자동 통과율 | 0.0% (0/4) |
| 요구사항 충족률 | 88.9% (8/9) |
| 평균 / p95 | 5.5초 / 14.2초 |

#### 기준선에서 분리해낸 원인

- 단기 기억은 코드명 `보라돌이`를 정확히 회상했다.
- 주제 전환 후에는 자격요건을 반복하지 않고 1인·8주·MVP 아이디어를 제시했다.
- Markdown 표, 대상 사용자, 수익 모델 계약도 충족했다.
- 그러나 **문서 검색을 하지 않은 모든 일반 답변에 `[출처]`를 붙였다.**
- 정체성 질문은 Qwen/알리바바 사칭은 피했지만 서비스 이름 `BTLLM`을 알지 못했다.

따라서 자동 0%를 전부 모델 이해력 실패로 해석하면 안 된다. 현재 공통 실패는 시스템 프롬프트의
“사용한 문서 파일명을 답변 마지막에 출처로 표시” 규칙이 비-RAG 답변까지 일반화된 영향이 크다.

### 5. 바로 이어서 할 작업 순서

1. **서비스 정체성과 조건부 출처 계약 수정**
   - 시스템 프롬프트에 “이 서비스는 BTLLM”을 명시한다.
   - `[출처]`는 `searchKnowledgeBase` 결과를 실제 사용했을 때만 표시하고, 일반 대화·사용자 입력에는 금지한다.
   - 동일 4문항을 최소 3회 반복해 기준선과 비교한다.
2. **Qwen3 8B 생성 파라미터 A/B**
   - A: 0.3 / 0.8 / 20
   - B: 0.5 / 0.8 / 20
   - C: 0.7 / 0.8 / 20
   - 단발 결과가 아니라 시나리오 통과율·요구사항·평균/p95·3회 안정성으로 선택한다.
3. **Qwen3.5 후보 비교**
   - 우선 후보: `qwen3.5:4b-q8_0`(약 5.3GB, 현 8B Q4와 비슷한 VRAM 범위).
   - `qwen3.5:9b`는 약 6.6GB라 bge-m3·KV cache·Windows GPU 사용량까지 합치면 CPU offload 위험이 크다.
4. **선택적 Thinking과 의도 라우팅**
   - 일반·문서 요약은 non-thinking, 기획·비교·복합 추론만 thinking을 실험한다.
   - RAG·대화검색·일반 아이디어 판단을 8B Tool Calling에 전부 맡기지 말고 애플리케이션 라우팅을 검토한다.
5. **VRAM 최적화**
   - `OLLAMA_FLASH_ATTENTION=1`, `OLLAMA_KV_CACHE_TYPE=q8_0`, `OLLAMA_NUM_PARALLEL=1` 비교 측정.
   - bge-m3의 GPU 상주 시간 또는 CPU 실행을 검토한 뒤 VRAM·TTFT·tokens/s로 판단한다.

### 6. 최신 검증 상태와 실행 명령

- 백엔드 일반 테스트: 31건 통과, 실패 0건. 실제 Ollama 실험은 일반 `test`에서 제외된다.
- 프론트엔드: `npm run lint`, `npm run build` 통과.
- 마지막 대화 품질 실험: 정상 완료, 4/4 결과 보고서 생성.

```powershell
cd C:\Users\User\claude-project\personal-llm-remaster\backend
.\gradlew.bat test
.\gradlew.bat localConversationQualityExperiment
```

파라미터 비교 예시:

```powershell
$env:LOCAL_MODEL_EVAL_TEMPERATURE = "0.5"
$env:LOCAL_MODEL_EVAL_REPETITIONS = "3"
.\gradlew.bat localConversationQualityExperiment
```

### 7. 주의사항

- RTX 4060 Ti 8GB에서 qwen3:8b 약 5.6GB + bge-m3 약 0.7GB라 VRAM 여유가 작다.
- 브라우저 등 GPU 프로그램 때문에 VRAM이 7.78GB까지 차면 생성 속도가 0.86t/s로 떨어진 전력이 있다.
- `keep-alive`는 단위 없는 `-1`이 아니라 반드시 `-1s`를 사용한다.
- 최신 대화 품질 보고서는 매 실험마다 덮어쓴다. 비교 결과를 보존하려면 실행 전후 별도 파일로 복사하거나
  다음 단계에서 설정별 결과 파일명을 도입한다.
- 현재 시스템 프롬프트의 출처 규칙을 고치기 전에 파라미터를 비교하면 모든 케이스가 같은 정책 오류로
  실패하므로 파라미터 효과가 가려진다.

---

## 0. 이 작업의 배경과 목표

주니어 백엔드 포트폴리오 강화가 목적이다. 국내 채용공고 분석 결과 **AI/AX 활용 역량, 장애·성능 이슈 해결 경험,
기술적 의사결정 서술력**이 핵심 평가축으로 확인됐고, 이미 보유한 `personal-llm`(BTLLM) 프로젝트가
그 영역을 가장 잘 커버한다고 판단해 **신규 프로젝트 대신 이 프로젝트를 심화**하기로 결정했다.

### 포트폴리오 서술 원칙 (합의된 사항 — 반드시 유지)

프로젝트당 4단 구조로 작성한다.

1. 프로젝트 오버뷰 (문제 정의 + 아키텍처)
2. 핵심 성과 지표 (정량 요약)
3. 주요 기술적 의사결정과 트레이드오프 (**설계 이전**에 저울질한 것)
4. 트러블슈팅 (**설계 이후** 발견한 문제)

**트러블슈팅은 반드시 `발견 → 진단 → 개선 → 검증` 4단계 각각에 수치가 있어야 한다.**
"느려서 고쳤다" 같은 정성적 서술은 금지. 수치화가 어색한 사례는 메인에서 빼거나 짧게 다룬다.

---

## 1. 이번 세션에서 커밋한 내용

`851528c` 이후 6개 커밋. **아직 push 안 됨** (`git push origin main` 필요).

| 커밋 | 내용 |
|---|---|
| `03ed2a8` | RAG 정확도 실험 하네스 + 골든셋 35건 |
| `4a3deeb` | Ollama keep-alive 수정 + RAG Tool 전환 + WS 안정성 개선 |
| `de53870` | 모바일 반응형 사이드바 |
| `fc26e83` | Tool 전환 후 RAG가 호출되지 않던 문제 수정 |
| `1ee50ee` | 지식베이스 상시 노출 + 사용 가이드 페이지 |
| `397e617` | 대화 기록 영속화 — 재시작 후에도 이어서 대화 가능 |

작성 당시 코드 변경은 모두 커밋됐으며, 이 `HANDOFF.md` 자체는 다음 작업자가 별도 커밋해야 한다.

---

## 2. 산출물 문서 (포트폴리오 원재료)

| 파일 | 내용 |
|---|---|
| `docs/rag-accuracy-experiment.md` | 청크 크기별 Recall@k 실측 결과표 |
| `docs/performance-ux-improvement-plan.md` | **가장 중요.** 성능·UX 이슈 9건 진단 + 개선 + 검증 수치. 트러블슈팅 섹션 원재료 대부분이 여기 있음 |
| `docs/btllm-devlog.md` | 기존 개발 일지 (이번 세션 이전 내용) |

---

## 3. 확보한 정량 데이터 (포트폴리오에 바로 쓸 수 있음)

### 3-1. RAG 청크 크기 실험 (`./gradlew ragAccuracyExperiment`)

골든셋 35건 · 코퍼스: Spring AI 공식 문서 7페이지 · 임베딩 bge-m3

| 청크 크기 | 청크 수 | 임베딩 소요 | Recall@5 | 운영설정 Recall(top5, th=0.5) |
|---|---|---|---|---|
| 800 | 66 | 31,418ms | 100% | 100% |
| **1500 (현재값)** | 35 | 6,555ms | 94.3% | **88.6%** |
| 3000 | 19 | 2,483ms | 97.1% | 82.9% |

**서사**: README에 있던 "청크 1500토큰으로 임베딩 호출 ~50% 감소"라는 주장에 **정확도 트레이드오프가 빠져 있었음**을
실측으로 드러냄. 임베딩 5배 절감의 대가로 운영설정 기준 Recall이 11.4%p 하락(hard 난이도는 85.7%까지).

### 3-2. Ollama 콜드스타트 (keep-alive)

| 조건 | 응답시간 |
|---|---|
| 콜드 (모델 언로드 후) | 37,197ms |
| 웜 (즉시 재호출) | 1,246ms |

**약 30배 차이.** `chat.options.keep-alive: -1s` 적용으로 해소.

### 3-3. 채팅 스트리밍 부하테스트 (`k6/chat-stream-test.js`)

동시 1/5/10명 단계. **가장 중요한 발견: 진짜 병목은 코드가 아니라 GPU였다.**

| 조건 | 결과 |
|---|---|
| VRAM 포화 (7.78GB/8GB) | 토큰생성 0.86 t/s, 요청 1건 2분55초, 33건 중 0건 완료 |
| VRAM 확보 후 (6.55GB) | 33건 중 4건 완료. TTFT avg 13.9s / p95 25.0s, 총응답 avg 16.9s |
| WS 연결 자체 | avg 2.6ms (문제 없음) |

VRAM을 비워도 **동시 2~3명이면 대부분 30초 초과** — 로컬 GPU 서빙 슬롯 1개 구조의 구조적 한계.
`nvidia-smi`에서 GPU-Util 100%인데 전력 46W/160W → 연산이 아닌 메모리 병목 신호로 진단.

---

## 4. 이번 세션의 트러블슈팅 사례 (포트폴리오 소재로 가치 높음)

### 4-1. 성능 개선 설정 한 줄이 채팅 전체를 마비시킨 회귀

- **발견**: keep-alive 적용 후 k6 baseline이 에러율 **100%**
- **진단**: 로컬 프록시(`http.server`)로 백엔드→Ollama 요청 가로채 원문 확보 →
  응답 본문 `{"error":"time: missing unit in duration \"-1\""}`
- **원인**: `keep-alive: -1`을 YAML에 정수로 썼으나 Spring AI의 `keepAlive` 필드가 String 타입이라
  `"-1"`로 직렬화 → Ollama의 Go `time.ParseDuration`이 단위 없는 값을 거부(400)
- **개선/검증**: `-1s`로 단위 명시 → 200 OK, `ollama ps`에서 `UNTIL: Forever`, 에러율 0% 복귀
- **교훈**: Spring Boot relaxed binding이 숫자를 조용히 문자열로 바꾸므로,
  설정값의 *형식*까지 실제 요청 로그로 검증해야 한다. 기동 성공 ≠ 동작 정상

### 4-2. Advisor→Tool 전환 후 RAG가 완전히 죽어 있던 문제

- **발견**: PDF 인덱싱 후 "방금 준 문서 무슨 문서야?" → "어떤 문서도 제공받지 않았어요"
- **진단 ①**: `vector_store` 직접 조회 → 21청크 정상 적재. ETL 문제 아님
- **진단 ②**: 도구 호출 로그 0건. Ollama API 직접 격리 테스트 →
  기존 description(`"…필요할 때만 사용하세요"`)은 `tool_calls: null`,
  단정형으로 바꾸면 정상 호출 → **원인은 description 어조**
- **개선**: description 억제형→단정형, 시스템 프롬프트에 지식베이스 존재·호출 조건 명시,
  도구 호출 로그 추가(원인 추적이 로그 부재로 어려웠음)
- **추가 발견**: 도구는 호출되나 **적중 0건**인 케이스 — "무슨 문서야?" 같은 *메타 질문*은
  문서 *내용*과 의미적으로 유사하지 않아 벡터 검색이 구조적으로 0건을 냄
  → 0건일 때 `EtlSourceService.listSources()`로 문서 목록을 대신 반환하도록 보완
- **교훈**: 상시 Advisor를 Tool로 바꾸는 건 성능 최적화처럼 보이지만 실제로는
  *동작 여부의 결정권을 모델에게 넘기는* 설계 변경. 작은 로컬 모델은 description 어조 하나로 호출률이 0%↔100%

### 4-3. 사용자 메시지가 저장되지 않던 문제

- **발견**: 새로고침 후 방을 열면 내 질문은 없고 AI 답변만 남음
- **진단**: `TokenTrackingAdvisor`가 ASSISTANT 응답만 `chat_histories`에 저장.
  추가로 `ddl-auto: create`라 기동할 때마다 계정·기록이 전부 삭제됨
- **개선**: `ddl-auto: update`로 전환, WebSocket 핸들러(원본 요청 문자열을 가진 지점)에서 USER 메시지 저장,
  정렬에 `id` 2차 기준 추가(응답이 짧으면 `createdAt`이 같아 순서 역전 가능)
- **검증**: 백엔드 완전 재시작 후 계정·방·이력 유지, 재시작 전 알려준 이름("홍길동")을 모델이 기억해 답변

---

## 5. 적용된 코드 변경 요약

### 백엔드

| 파일 | 변경 |
|---|---|
| `application.yaml` | `ddl-auto: create → update`, `ollama.chat.options.keep-alive: -1s` 추가 |
| `config/ChatClientFactory.java` | Advisor 체인 5→4단계 (RAG Advisor 제거), 시스템 프롬프트에 지식베이스 안내 추가 |
| `chat/tools/LlmTools.java` | `searchKnowledgeBase` Tool 신규(RAG), 검색 0건 시 문서 목록 반환, 호출 로그 |
| `chat/advisor/SafeQuestionAnswerAdvisor.java` | **삭제** (Tool로 대체) |
| `chat/controller/ChatWebSocketHandler.java` | USER 메시지 영속화 추가 |
| `chat/repository/ChatHistoryRepository.java` | 정렬에 id 2차 기준 추가 |
| `build.gradle` | `ragAccuracyExperiment` 태스크 추가(일반 test에서는 `experiment` 태그 제외) |

### 프론트엔드

| 파일 | 변경 |
|---|---|
| `hooks/useWebSocket.ts` | 지수 백오프 자동 재연결(최대 5회), 연결 전 전송 큐잉 |
| `pages/ChatPage.tsx` | 재연결 상태 배지, 에러 "다시 시도" 버튼, 모바일 반응형 사이드바, 지식베이스 패널 배치 |
| `components/KnowledgePanel.tsx` | **신규** — 사이드바 지식베이스 상시 노출 + 2단계 확인 삭제 |
| `stores/knowledgeStore.ts` | **신규** — 지식베이스 목록 전역 상태(사이드바↔모달 동기화) |
| `pages/AboutPage.tsx` | **신규** — `/about` 공개 사용 가이드 |
| `components/RagUploadModal.tsx` | 로컬 state → 스토어 사용으로 전환 |
| `pages/LoginPage.tsx`, `App.tsx` | `/about` 라우트 및 진입점 |

---

## 6. 미해결 — 다음 작업자가 이어받을 것

### 6-1. 【최우선】 RAG 답변 품질 (진단 완료, 수정 미적용)

실제 PDF(34페이지 정부 공고문)로 테스트한 결과 **답변 품질이 낮다.**
"프로젝트에 대해 간략히 설명" 질문에 대해 사업 목적·트랙 구조·선정규모·상금·신청처가 **전부 누락**되고,
부록의 *중복참여 불가 사업 목록*을 마치 이 사업이 제공하는 지원 목록인 것처럼 제시했다(오해 유발).

**측정으로 확인한 것:**

| 단계 | 상태 |
|---|---|
| 인덱싱 | ✅ 정상 (원본 29,925자 → 인덱싱 31,096자, 손실 없음) |
| 검색 | ✅ 정상 — 직접 벡터 검색 시 1위 0.671(사업 개요), 2위 0.665(트랙·선정규모)로 **정답 청크가 상위** |
| 생성 | ❌ **실패** — 올바른 근거를 받고도 무시하고 3~5위 부수 내용으로 답 구성 |

**원인 가설 3가지:**
1. **RAG 프롬프트 템플릿 소실** — Advisor→Tool 전환 때 기존 `QuestionAnswerAdvisor`의
   "이 컨텍스트로 질문에 답하라" 템플릿이 사라짐. 현재는 청크 5개를 `---`로 이어붙인 raw 덩어리만 전달.
   유사도 순위 정보도, 사용 지침도 없음
2. **모델 체급** — qwen3:8b가 7,500자 정부문서를 받고 "질문에 답하기"가 아니라 "본 것 전부 요약"으로 처리
3. **`num_ctx` 4096 (잠재 폭탄)** — 해당 요청이 3,729/4,096 토큰으로 **간신히 안 잘림**.
   청크가 조금만 길거나 답변이 길면 그대로 truncate

**제안 수정 (사용자 승인 대기 상태였음):**
1. 도구 결과에 순위·출처 표기 + 답변 지침 추가 (`[관련도 1위] …`, "질문에 직접 답하고 근거만 인용")
2. `num_ctx`를 8192로 상향 (4096은 RAG에 부족)
3. topK/청크 길이 조정 실험 (topK=5 × 1500자는 8B 모델에 과할 수 있음)

1~2번은 확실한 개선, 3번은 실험 필요.

### 6-2. 메시지 리스트 가상화 (`docs/…-plan.md` #8)

`react-virtuoso` 도입을 시도했다가 **되돌렸다.** 브라우저 검증 중 `virtuoso-item-list`가 비는 문제가 있었고,
당시 브라우저 패널 뷰포트가 0x0으로 잡히는 환경 문제와 뒤엉켜 원인 분리가 안 됐다.
포트폴리오/데모 규모에서는 체감되지 않으므로 **백로그로 두는 것을 권장**한다.
(`react-virtuoso`는 `package.json`에 남아 있을 수 있으니 확인 후 미사용이면 제거)

### 6-3. 아직 착수 안 한 것들

- SafeGuardAdvisor 오차단(false positive) 비율 측정 — 정상 질의 골든셋 30건 필요
- 동시 요청 대기 안내 (`WsResponse`에 `QUEUED` 타입 추가) — `plan.md` #2 (b)안
- **포트폴리오 문서 본문 작성** — 위 4단 구조로. 원재료는 `docs/` 2개 문서에 다 있음
- AI 협업 워크플로우 섹션 — 이 세션 자체(Claude Code로 실험 설계·측정·디버깅)가 AX 역량 증거
- `git push origin main` (커밋 6개 미푸시)

---

## 7. 로컬 실행 방법

### 사전 조건

```bash
ollama pull qwen3:8b
ollama pull bge-m3
```

Docker Desktop 실행 필요.

### 기동

```bash
docker compose up -d db
```

```bash
cd backend && ./gradlew bootRun
```

```bash
cd frontend && npm install && npm run dev
```

- 백엔드 http://localhost:8080 / 프론트 http://localhost:5173 / DB localhost:5433

**주의**: Anthropic·Google API 키가 없으면 해당 `ChatModel` 빈 생성이 실패해 **앱 기동 자체가 실패**한다.
로컬에서 Ollama만 쓸 경우 더미 값을 넣어 우회한다.

```bash
SPRING_AI_ANTHROPIC_API_KEY=dummy GOOGLE_AI_API_KEY=dummy ./gradlew bootRun
```

### 실험·부하테스트

```bash
cd backend && ./gradlew ragAccuracyExperiment
```

```bash
cd k6 && k6 run chat-stream-test.js
```

`ragAccuracyExperiment`는 실 Ollama + pgVector가 필요하며 일반 `test` 태스크에서는 `experiment` 태그로 제외된다.
결과는 `docs/rag-accuracy-experiment.md`에 덮어쓴다.

---

## 8. 환경 및 제약 (측정 결과에 직접 영향)

- GPU: **RTX 4060 Ti 8GB** — qwen3:8b(5.6GB) + bge-m3(0.7GB)로 VRAM이 빠듯하다.
  브라우저 등 GPU를 쓰는 프로그램이 함께 떠 있으면 추론이 30~60배 느려진다(측정치 0.86 t/s).
  **성능 측정 시에는 다른 GPU 프로그램을 모두 종료할 것.**
- 테스트 계정: `persisttest@test.com` / `persist1234` (이전 대화 이력 포함).
  `ddl-auto: update`로 바꿨으므로 이제 재시작해도 유지된다.
- 지식베이스에 테스트용 PDF 1건(21청크)이 인덱싱돼 있다 —
  `(제2026-511호)「모두의_창업_프로젝트」_통합_모집_공고_(2차).pdf`
