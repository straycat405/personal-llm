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
| Frontend | React (미시작) |

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

#### ❌ 미완 (다음 작업 대상)
| 항목 | 우선순위 | 비고 |
|---|---|---|
| `ChatWebSocketHandler` | ★★★ 최우선 | WebSocketConfig 참조 → 현재 **컴파일 에러** |
| `UserService` + `AuthController` | ★★★ | 회원가입 / 로그인 REST API |
| JWT 필터 (`JwtAuthFilter`) | ★★★ | `SecurityConfig`에 추가 |
| `ChatRoomService` + `ChatRoomController` | ★★ | 방 생성·조회·삭제 |
| `docker-compose.yml` | ★★ | PostgreSQL + pgVector 로컬 개발 환경 |
| Frontend React 기본 구조 | ★ | Vite + React 세팅 (Phase 1 마무리) |

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

### 2026-05-23

**작업 내용:**
- BTLLM 리뉴얼 작업 공간 확인 (`projects/btllm`)
- 현재 코드 상태 전수 조사
- 아키텍처 다이어그램 HTML 생성 (`images/btllm-architecture.html`)
- 개발 일지(이 파일) 생성

**발견 사항:**
- Spring Boot 3.5.14 + Java 17 세팅 이미 완료
- Entity 3종 + Repository 3종 + Config 3종 이미 커밋됨
- `WebSocketConfig`가 `ChatWebSocketHandler`를 import → 파일 없어서 **빌드 불가** 상태
- Frontend 디렉토리 비어있음

**다음 세션 시작점:**
1. `ChatWebSocketHandler` 스텁 생성 → 빌드 통과 복구
2. `docker-compose.yml` 작성 → DB 기동 확인
3. `UserService` + `AuthController` (회원가입/로그인)
4. JWT 필터 추가
