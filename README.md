# BTLLM - 셀프 제작 챗봇

> Java 8 + eGovFramework 기반 LLM을  
> Java 17 + Spring Boot 3.x + Spring AI로 리팩토링

**8GB VRAM 개인 PC에서 문서가 외부로 나가지 않는 로컬 RAG 문서 비서.**
골든셋 기반 자동 평가 하네스로 개선을 측정한다.

| 문서 | 내용 |
|---|---|
| [docs/portfolio.md](docs/portfolio.md) | **포트폴리오 본문** — 성과 지표, 기술 의사결정, 트러블슈팅 |
| [docs/portfolio-improvement-log.md](docs/portfolio-improvement-log.md) | 개선 과정 전체 기록 (가설·실패·검증) |
| [HANDOFF.md](HANDOFF.md) | 작업 인수인계 — 현재 상태와 다음 과제 |

---

## 기술 스택

| 구분 | 기술 |
|---|---|
| Backend | Java 17, Spring Boot 3.5, Spring AI 1.1.6, Spring Security |
| AI | Ollama (qwen3:8b), Spring AI Advisor Chain |
| RAG | pgVector, bge-m3 임베딩 모델 (1024차원) |
| Database | PostgreSQL 17 |
| Frontend | React 18, TypeScript, Vite, Tailwind CSS |
| Infra | Docker Compose, GitHub Actions CI |

---

## 핵심 구현

### 1. Spring AI Advisor 체인 - 관심사 분리

```
WebSocket 메시지 수신
        │
        ▼
  ChatWebSocketHandler  ← 스트리밍 전송만 담당
        │
        ▼
   Spring AI ChatClient
        │
        ├─ ① SafeGuardAdvisor          (HIGHEST_PRECEDENCE)
        │       부적절 입력을 LLM 호출 전 차단
        │
        ├─ ② MessageChatMemoryAdvisor   (0)
        │       대화 맥락 자동 주입 (JdbcChatMemoryRepository)
        │
        ├─ ③ TokenTrackingAdvisor       (LOWEST - 2)
        │       실제 usage 메타데이터 기반 토큰 추적 + DB 저장
        │
        └─ ④ SimpleLoggerAdvisor        (LOWEST - 1)
                개발용 디버그 로그
```

WebSocketHandler는 스트리밍 전송에만 집중하고,  
메모리 관리·토큰 추적은 각 Advisor가 독립적으로 처리.
RAG(문서 검색)는 상시 Advisor가 아니라 아래 Tool Calling으로 처리한다 —
모든 메시지마다 무조건 벡터 검색하지 않고, 모델이 필요하다고 판단할 때만 호출한다.

### 2. LLM Tool Calling - Agentic 패턴

LLM이 대화 맥락에 따라 도구를 자율 선택:

| 도구 | 역할 |
|---|---|
| `crawlWebPage` | URL → Jsoup 크롤링, 3000자 요약 |
| `searchChatHistory` | 키워드 기반 과거 대화 검색 |
| `searchKnowledgeBase` | pgVector 유사도 검색 (RAG) — 문서 기반 질문일 때만 호출 |
| `getTokenUsage` | 누적 토큰·비용 조회 |

**설계 변경**: 초기에는 `SafeQuestionAnswerAdvisor`로 모든 메시지에 상시 RAG 검색을 걸었으나,
잡담에도 불필요한 임베딩 호출이 발생하고 무관한 청크가 컨텍스트를 오염시키는 문제가 있어
Tool로 전환했다 (자세한 배경은 [docs/performance-ux-improvement-plan.md](docs/performance-ux-improvement-plan.md) #4 참고).

### 3. RAG ETL 파이프라인 - 비동기 + 실시간 진행률

다양한 소스를 pgVector에 적재하고 SSE로 진행률을 클라이언트에 실시간 push:

```
URL     → Jsoup.connect()         (JS 렌더링 제외 일반 페이지)
PDF     → PagePdfDocumentReader
DOCX    → TikaDocumentReader
XLSX    → TikaDocumentReader
PPTX    → TikaDocumentReader
TXT     → TikaDocumentReader
          │
          ▼  @Async (즉시 202 반환)
    TokenTextSplitter
    (800토큰, 문장 단위 분할)
          │
          ▼
   DocumentSummarizer
   (문서 개요 요약 청크 1개 추가)
          │
          ▼
      pgVector 저장
          │
          ▼
  SSE push (진행률 0~100%)   ← EventSource로 프론트 실시간 수신
```

**설계 포인트:**
- `POST /etl/*` → 202 Accepted + `jobId` 즉시 반환 (동기 처리 시 HTTP 타임아웃 위험)
- `EtlProgressTracker`: `ConcurrentHashMap`으로 jobId별 진행 상태 관리
- SSE 엔드포인트 `permitAll`: EventSource는 커스텀 헤더 미지원 → UUID jobId로 접근 제어 대체
- `TokenTextSplitter` 청크 크기 **800토큰**: 한때 1500을 썼으나 한국어 공고문에서 평균 5,425자
  청크가 만들어져 `topK=3` 근거만 약 4,521토큰이 되고 `num_ctx` 4096을 초과했다. 800으로 낮춰
  골든셋 통과율이 0% → 50%로 올랐다 ([docs/portfolio.md](docs/portfolio.md) 8장)

### 4. 지식베이스 관리 인터페이스

인덱싱된 문서를 목록 조회·삭제할 수 있는 관리 UI:

- `GET /api/v1/admin/etl/sources` — source별 청크 수 집계 (JdbcTemplate 직접 SQL)
- `DELETE /api/v1/admin/etl/sources?source=` — 특정 소스의 모든 청크 삭제
- 프론트엔드: 탭 3종 (URL 크롤링 / 파일 업로드 / 지식베이스)

---

## 원본 vs 개선 비교

| 항목 | 원본 (As-Is) | 개선 (To-Be) |
|---|---|---|
| Java | 8 | **17** |
| 프레임워크 | eGovFramework 4.2.0 (Spring MVC 5.3) | **Spring Boot 3.5** |
| AI 연동 | 외부 LLM API 직접 HTTP 호출 | **Spring AI 1.1.6 + Advisor 체인** |
| 프론트 | JSP + Tiles (서버사이드 렌더링) | **React + TypeScript + Vite** |
| URL 패턴 | `*.do` | **REST `/api/v1/...`** |
| 세션 관리 | ConcurrentHashMap 4개 수동 관리 | **MessageChatMemoryAdvisor** |
| 토큰 추적 | 휴리스틱 추정 (TokenCalculatorUtil) | **실제 usage 메타데이터 (StreamAdvisor)** |
| RAG | 파일 업로드 임시 처리 | **비동기 ETL + pgVector + SSE 진행률** |
| 파일 지원 | PDF만 | **PDF·DOCX·XLSX·PPTX·TXT (Tika)** |
| 지식베이스 관리 | 없음 | **목록 조회 + 개별 삭제 UI** |
| 테스트 | 없음 | **Mockito 단위 + MockMvc 슬라이스** |
| CI/CD | 없음 | **GitHub Actions** |

---

## 로컬 실행

### 사전 조건

[Ollama](https://ollama.com) 설치 후 모델 pull:

```bash
ollama pull qwen3:8b   # LLM
ollama pull bge-m3     # 임베딩
```

### Docker Compose 실행 (백엔드 + DB)

`JWT_SECRET`·`POSTGRES_PASSWORD`·`GRAFANA_ADMIN_PASSWORD` 중 하나라도 미설정이면 기동이
실패한다(안전 실패) — 먼저 루트 `.env`를 준비한다.

```bash
cp .env.example .env
# .env를 열어 아래 세 값을 채운다
#   JWT_SECRET=$(openssl rand -base64 32)
#   POSTGRES_PASSWORD=$(openssl rand -base64 24)
#   GRAFANA_ADMIN_PASSWORD=$(openssl rand -base64 18)
docker compose up -d
```

| 루트 `.env` 변수 | 용도 |
|---|---|
| `JWT_SECRET` | JWT 서명 키. 32바이트 미만/알려진 placeholder면 백엔드 기동 실패 |
| `POSTGRES_PASSWORD` | PostgreSQL 비밀번호. `db`·`backend` 두 서비스가 공유 |
| `GRAFANA_ADMIN_PASSWORD` | Grafana admin 계정 비밀번호(기존 `admin/admin` 고정값 대체) |

> **주의**: `db`(5433)·`prometheus`(9090)·`grafana`(3000)·`loki`(3100)는 모두 `127.0.0.1`에만
> 바인딩된다 — 같은 호스트에서는 그대로 접속되지만 다른 기기(LAN)에서는 접근할 수 없다.
> 서비스 간 통신(Grafana→Prometheus/Loki, backend→db)은 host 포트가 아니라 Docker 내부
> 네트워크(서비스명)로 이루어지므로 영향받지 않는다.

### 프론트엔드 개발 서버

```bash
cd frontend
npm install
npm run dev
```

| 서비스 | 주소 |
|---|---|
| 백엔드 API | http://localhost:8080 |
| 프론트엔드 | http://localhost:5173 |
| PostgreSQL | localhost:5433 |

### 환경 변수

API 키는 `backend/.env`로 주입한다(`.gitignore` 대상). 템플릿을 복사해 채운다.

```bash
cd backend && cp .env.example .env
```

| 변수 | 용도 |
|---|---|
| `SPRING_AI_OPENAI_API_KEY` | OpenAI(ChatGPT) provider 활성화 |
| `SPRING_AI_ANTHROPIC_API_KEY` | Anthropic(Claude) provider 활성화 |
| `GOOGLE_AI_API_KEY` | Google(Gemini) provider 활성화 |
| `JWT_SECRET` | 직접 실행: 미설정 시 기동마다 랜덤(안전). **Compose/프로덕션: 미설정이면 기동 자체가 실패한다.** `openssl rand -base64 32`로 생성해 루트 `.env`(compose용, `.env.example` 참고)에 저장. 32바이트 미만이거나 알려진 placeholder(`changeme` 등)도 기동 실패 |

Ollama(로컬)는 키가 필요 없다. 키가 없는 provider는 `/api/v1/models`에서 `available=false`로
표시되고 UI에서 선택이 막힌다.

> **주의** Google provider는 키가 비어 있으면 Spring AI의 `CachedContentService`가
> 컨텍스트 기동 단계에서 실패한다(우리 `ChatClientFactory`의 예외 흡수 범위 밖).
> Gemini를 쓰지 않으면 `GOOGLE_AI_API_KEY=dummy`처럼 임의 값을 넣어 기동을 통과시킨다.

---

## 테스트

```bash
cd backend
./gradlew test
```

- `ChatRoomServiceTest` — Mockito 단위 테스트 (DB 불필요)
- `ChatRoomControllerTest` — MockMvc 슬라이스 테스트 (DB 불필요)
- `HybridRerankerTest` — RAG 검색 재정렬 로직 단위 테스트 (DB 불필요)

### 품질 평가 실험

실제 Ollama·pgVector(및 상용 API)를 호출하는 장시간 실험은 일반 `test`에서 태그로 제외된다.
결과는 `docs/` 아래 보고서로 매 실행마다 덮어쓴다.

```bash
cd backend
./gradlew ragAccuracyExperiment              # 청크 크기별 검색 Recall
./gradlew ragGenerationQualityExperiment     # PDF 기반 답변 품질
./gradlew localConversationQualityExperiment # 대화 품질(정체성·기억·출력 계약)
./gradlew providerComparisonExperiment       # 동일 골든셋 로컬 vs 상용 모델 비교
```

LLM을 호출하지 않고 **검색 단계만** 관측하는 진단 도구도 있다. 최종 답변만 보면
"근거가 없어서 틀린 것"과 "근거를 받고도 못 쓴 것"을 구분할 수 없기 때문이다.

```bash
./gradlew trackAttributionDiagnostic   # 특정 문항의 근거 존재 여부 + topK별 비교
./gradlew summaryRetrievalDiagnostic   # 요약 청크가 개요형 질의에 검색되는지
```

색인 로직(청크 크기·요약 청크)을 바꾼 뒤에는 재색인해야 평가가 유효하다.

```bash
REINDEX_PDF_PATH='/path/to/문서.pdf' ./gradlew reindexDocument
```

주요 실험 파라미터:

| 환경변수 | 기본값 | 설명 |
|---|---|---|
| `BTLLM_RAG_CHUNK_SIZE` | 800 | 색인 청크 크기(토큰). 변경 시 재색인 필요 |
| `BTLLM_THINKING` | true | qwen3 thinking. 끄면 약 2.7배 빠르나 정확도 하락 |
| `BTLLM_NUM_PREDICT` | 2048 | 출력 상한(폭주 방지용, 최적화 수단 아님) |
| `PROVIDER_COMPARISON_REPETITIONS` | 1 | provider 비교 반복 횟수 |

`providerComparisonExperiment`는 **상용 API 실제 과금이 발생한다.** 대상은 환경변수로 바꾼다.

```bash
PROVIDER_COMPARISON_TARGETS='ollama=qwen3:8b,openai=gpt-4o' ./gradlew providerComparisonExperiment
```

---

## CI/CD

GitHub Actions (`ci.yml`):

| Job | 내용 |
|---|---|
| `backend-test` | Gradle 테스트 실행 |
| `frontend-build` | TypeScript 타입 체크 + Vite 빌드 |
| `docker-build` | Docker 이미지 빌드 검증 (`push: false`) |

`docker-build`는 `backend-test` 통과 후에만 실행.
