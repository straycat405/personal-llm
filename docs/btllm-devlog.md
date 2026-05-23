# BTLLM 리뉴얼 개발 일지

작업 경로: `C:\Users\User\portfolio-20260519\projects\btllm`
GitHub: straycat405/personal-llm

---

## 스택

| 항목 | 버전 |
|---|---|
| Java | 17 |
| Spring Boot | 3.5.14 |
| Spring AI | 1.1.6 |
| LLM (로컬) | Ollama — exaone3.5:7.8b |
| 임베딩 | Ollama — bge-m3 (1024차원) |
| DB | PostgreSQL + pgVector |
| Frontend | React (Vite + TypeScript + Tailwind CSS) |

---

## Phase 진행 상황

### Phase 1 — 기반 현대화
**목표:** Spring Boot 3.x + Java 17 + REST API + Docker + React 기본 구조

#### ✅ 완료
| 항목 | 파일 | 완료일 |
|---|---|---|
| Gradle 빌드 설정 | `backend/build.gradle` | 2026-05-20 |
| application.yaml | `backend/src/main/resources/application.yaml` | 2026-05-20 |
| Entity: User | `user/entity/User.java` | 2026-05-20 |
| Entity: ChatRoom | `chat/entity/ChatRoom.java` | 2026-05-20 |
| Entity: ChatHistory | `chat/entity/ChatHistory.java` | 2026-05-20 |
| Entity: MessageRole | `chat/entity/MessageRole.java` | 2026-05-20 |
| Repository: UserRepository | `user/repository/UserRepository.java` | 2026-05-20 |
| Repository: ChatRoomRepository | `chat/repository/ChatRoomRepository.java` | 2026-05-20 |
| Repository: ChatHistoryRepository | `chat/repository/ChatHistoryRepository.java` | 2026-05-20 |
| Config: AiConfig (ChatClient 빈) | `config/AiConfig.java` | 2026-05-20 |
| Config: SecurityConfig (개발용) | `config/SecurityConfig.java` | 2026-05-20 |
| Config: WebSocketConfig | `config/WebSocketConfig.java` | 2026-05-20 |
| 아키텍처 다이어그램 HTML | `portfolio/images/btllm-architecture.html` | 2026-05-23 |

#### ✅ Phase 1 전체 완료 (2026-05-23)
| 항목 | 비고 |
|---|---|
| `ChatWebSocketHandler` 스텁 | Phase 2 교체 예정 |
| `JwtProvider` + `JwtAuthFilter` + `AuthUser` | HMAC-SHA, User Enumeration 방지 |
| `UserService` + `AuthController` | 회원가입/로그인 REST API |
| `ChatRoomService` + `ChatRoomController` | UUID conversationId, 소유자 검증 |
| `BusinessException` + `ErrorCode` + `GlobalExceptionHandler` | 예외 처리 중앙화 |
| `ApiResponse<T>` 공통 래퍼 | 응답 형식 통일 |
| `docker-compose.yml` | pgvector:pg17, 포트 5433 (로컬 PG18 충돌 우회) |
| React 기본 구조 | Vite + TS + Tailwind + Zustand + React Router |
| End-to-End 기동 테스트 | 회원가입 → 로그인 → 채팅방 UI 동작 확인 ✅ |

---

### Phase 2 — WebSocket 스트리밍 + Advisor 체인
**목표:** LLM 실시간 스트리밍 + Spring AI Advisor 5종 적용

**상태:** 미시작 (Phase 1 완료 후)

구현 목록:
- `ChatWebSocketHandler` 구현 (Flux 스트리밍)
- `SafeGuardAdvisor` 적용
- `MessageChatMemoryAdvisor` + `JdbcChatMemoryRepository`
- `TokenTrackingAdvisor` 커스텀 구현
- `SimpleLoggerAdvisor` (개발용)

---

### Phase 3 — RAG + Tool Calling
**상태:** 미시작

구현 목록:
- ETL 파이프라인: JsoupDocumentReader / PagePdfDocumentReader / TikaDocumentReader
- TokenTextSplitter → KeywordMetadataEnricher → pgVector 저장
- `RetrievalAugmentationAdvisor` + `CompressionQueryTransformer`
- Tool Calling 3종: 웹 크롤러, 히스토리 검색, 사용량 조회

---

### Phase 4 — MCP SSE 서버 (선택)
**상태:** 미시작 (Phase 3 완료 후 시간 여유 시)

---

## 세션별 작업 기록

### 2026-05-23 — Phase 1 완료

**작업 내용:**
- BTLLM 리뉴얼 작업 공간 확인 (`projects/btllm`)
- 현재 코드 상태 전수 조사 → 빌드 불가 상태 확인 및 복구
- 아키텍처 다이어그램 HTML 생성 (`images/btllm-architecture.html`)
- Phase 1 전체 백엔드 구현 완료
- React 프론트엔드 기본 구조 완성
- End-to-End 기동 테스트 통과

**트러블슈팅:**
1. `ChatWebSocketHandler` 누락 → 컴파일 에러 → 스텁 생성으로 해결
2. PostgreSQL 비밀번호 인증 실패
   - 원인: 로컬 PostgreSQL 18이 5432 선점 → Docker 컨테이너 대신 로컬 PG에 접속
   - 해결: docker-compose.yml 포트 `5432→5433` 변경, application.yaml 동기화

**다음 세션 시작점: Phase 2**
- Ollama 기동 확인 (exaone3.5:7.8b, bge-m3 모델 pull)
- `ChatWebSocketHandler` 실 구현 (Spring AI ChatClient + Flux 스트리밍)
- Spring AI Advisor 체인 적용 (SafeGuard → Memory → TokenTracking → Logger)
- React 채팅 UI WebSocket 연결 구현
