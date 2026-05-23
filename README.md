# BTLLM - 셀프 제작 챗봇

> Java 8 + eGovFramework 기반 LLM을  
> Java 17 + Spring Boot 3.x + Spring AI로 리팩토링

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
        ├─ ② SafeQuestionAnswerAdvisor  (HIGHEST_PRECEDENCE + 1)
        │       pgVector 유사도 검색 → 프롬프트에 컨텍스트 주입 (RAG)
        │
        ├─ ③ MessageChatMemoryAdvisor   (0)
        │       대화 맥락 자동 주입 (JdbcChatMemoryRepository)
        │
        ├─ ④ TokenTrackingAdvisor       (LOWEST - 2)
        │       실제 usage 메타데이터 기반 토큰 추적 + DB 저장
        │
        └─ ⑤ SimpleLoggerAdvisor        (LOWEST - 1)
                개발용 디버그 로그
```

WebSocketHandler는 스트리밍 전송에만 집중하고,  
메모리 관리·RAG·토큰 추적은 각 Advisor가 독립적으로 처리

### 2. LLM Tool Calling - Agentic 패턴

LLM이 대화 맥락에 따라 도구를 자율 선택:

| 도구 | 역할 |
|---|---|
| `crawlWebPage` | URL → Jsoup 크롤링, 3000자 요약 |
| `searchChatHistory` | 키워드 기반 과거 대화 검색 |
| `getTokenUsage` | 누적 토큰·비용 조회 |

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
    (1500토큰, 문장 단위 분할)
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
- `TokenTextSplitter` 청크 크기 1500토큰: 기본값 800 대비 임베딩 호출 ~50% 감소

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

```bash
docker compose up -d
```

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

```bash
# 기본값 있음 — 프로덕션 배포 시 반드시 변경
JWT_SECRET=your-secret-key
```

---

## 테스트

```bash
cd backend
./gradlew test
```

- `ChatRoomServiceTest` — Mockito 단위 테스트 (DB 불필요)
- `ChatRoomControllerTest` — MockMvc 슬라이스 테스트 (DB 불필요)

---

## CI/CD

GitHub Actions (`ci.yml`):

| Job | 내용 |
|---|---|
| `backend-test` | Gradle 테스트 실행 |
| `frontend-build` | TypeScript 타입 체크 + Vite 빌드 |
| `docker-build` | Docker 이미지 빌드 검증 (`push: false`) |

`docker-build`는 `backend-test` 통과 후에만 실행.
