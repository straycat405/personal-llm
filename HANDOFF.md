# 인수인계 문서

작성일: 2026-08-26
작업 경로: `C:\Users\User\claude-project\personal-llm-remaster`
저장소: https://github.com/straycat405/personal-llm (브랜치 `main`)

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
