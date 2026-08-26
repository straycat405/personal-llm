# 성능·UX 개선안

코드 리뷰(백엔드 Advisor 체인/WebSocket, 프론트 채팅 UI) + 기존 RAG 정확도 실험 결과를 근거로,
"실사용자가 불편함을 느낄 지점" 기준으로 진단하고 개선안을 정리한다.
정량 검증이 안 된 항목은 "측정 필요"로 명시 — 추측을 사실처럼 쓰지 않는다.

---

## 우선순위 요약

| # | 이슈 | 체감 영역 | 난이도 | 우선순위 |
|---|---|---|---|---|
| 1 | 채팅 스트리밍 성능 자체가 측정된 적 없음 — 측정해보니 GPU VRAM 병목 확인 | 처리속도 | 중 | **최우선(완료)** |
| 2 | Ollama 동시 요청 처리 한계 — 실측으로 확인됨 | 처리속도 | 진단 하 | **최우선(확인됨)** |
| 3 | 유휴 후 모델 콜드스타트 지연 | 처리속도 | **하** | **최우선** |
| 4 | 모든 메시지에 무조건 RAG 검색 실행 | 처리속도+정확도 | 중 | 높음(완료) |
| 5 | WebSocket 끊기면 자동 복구 없음 | 안정성 | 중 | 높음(완료) |
| 6 | 스트리밍 에러 시 재전송 수단 없음 | 안정성 | 하 | 중간(완료) |
| 7 | WS 연결 전 전송 시 메시지 유실 가능 | 안정성 | 하 | 중간(완료) |
| 8 | 긴 대화 시 메시지 리스트 가상화 없음 | 장시간 사용 | 중~상 | 낮음(시도 후 되돌림 — 아래 기록) |
| 9 | 모바일 레이아웃 미대응 | 디바이스 | 중 | 낮음(완료) |

---

## 1. 채팅 스트리밍 성능 자체가 측정된 적 없음

**근거**: [k6/load-test.js](../k6/load-test.js) — 시나리오가 채팅방 생성/목록/이력조회/삭제(REST CRUD)뿐이다.
`/ws/chat` 스트리밍 엔드포인트를 건드리는 시나리오가 없다.

**문제**: 지금까지의 성능 튜닝(HikariCP maximum-pool-size 30, Tomcat threads 400 — [application.yaml:12](../backend/src/main/resources/application.yaml:12))은
전부 REST API 대상이다. 정작 사용자가 매번 체감하는 "메시지 보내고 답 오기까지" 속도와 동시 접속 시 지연은 한 번도 측정된 적이 없다.

**개선안**: k6 `ws` 모듈로 시나리오 추가 — 로그인 → WS 연결 → 메시지 전송 → 첫 토큰까지 시간(TTFT)·전체 완료까지 시간 측정.
동시 사용자 1 / 5 / 10명 단계로 늘려가며 TTFT 분포(p50/p95) 확인.

**적용 완료 — [k6/chat-stream-test.js](../k6/chat-stream-test.js)**

### 측정 결과 — 그리고 도중에 잡은 진짜 병목

1차 실행은 채팅 자체가 100% 실패했다 — 원인은 항목 #3에서 넣은 `keep-alive` 설정 오타(문자열 `"-1"`을 Ollama가
duration 파싱 실패로 400 거부)였다. 이건 그 자체로 "성능 개선 시도가 프로덕션 기능을 깨뜨릴 뻔한" 사례라 #3에 별도로 기록했다.

버그를 고치고 2차로 실행했더니 이번엔 통과는 했지만 **32건 중 대부분이 30초 안전 타임아웃에 걸려 응답을 못 받았다**.
원인을 추적해보니 Ollama 서버 로그의 토큰 생성 속도가 `tg = 0.86 t/s` — RTX 4060 Ti + qwen3:8b(Q4_K_M) 조합에서
정상적으로 나와야 할 수치(대략 20~50+ t/s)에 한참 못 미쳤다. `nvidia-smi` 확인 결과:

```
GPU Memory-Usage: 7784MiB / 8188MiB   (여유 404MiB)
GPU-Util: 100%    Power: 46W / 160W   ← util은 100%인데 전력은 거의 안 씀 = 연산이 아니라 메모리 병목 신호
```

8GB VRAM 카드에 qwen3:8b(5.6GB)+bge-m3(664MB)+이 세션이 띄워둔 브라우저 패널(Chrome GPU 프로세스)까지 올라가 있어
VRAM이 실질적으로 꽉 찼고, Windows WDDM이 초과분을 시스템 메모리로 페이징하면서 추론 속도가 30~60배 느려진 것으로 보인다.
브라우저 패널을 닫아 VRAM을 7.78GB → 6.55GB로 낮추고 3차로 재실행한 결과:

| 지표 | 값 |
|---|---|
| 완료된 요청(30초 이내) | 33건 중 4건 (12%) |
| TTFT (완료분 기준) | avg 13.9s / median 10.6s / p95 25.0s |
| 총 응답시간 (완료분 기준) | avg 16.9s / median 16.0s / p95 21.0s |
| WS 연결 자체 소요 | avg 2.6ms (문제 없음) |

**해석**: VRAM을 비우자 완전 무응답(0/32)에서 일부 완료(4/33)로 나아졌지만, 여전히 대다수(88%)는 30초 안에 못 끝났다.
즉 이 로컬 환경에서는 (a) VRAM 여유가 없으면 사실상 먹통 수준으로 느려지고, (b) VRAM을 확보해도 **동시 요청이 몰리면
GPU 슬롯 1개가 순차 처리하느라 대부분 30초를 넘긴다** — 항목 #2가 의심이 아니라 확인된 사실로 바뀐 이유다.

**결론**: 이 프로젝트의 실제 응답 속도는 코드 로직보다 로컬 GPU 자원(VRAM 여유·동시 처리 슬롯)에 압도적으로 좌우된다.
포트폴리오/데모 환경에서는 "시연 중엔 다른 GPU 프로그램을 켜두지 않는다"가 코드 최적화보다 먼저 챙겨야 할 조건이고,
실서비스라면 로컬 Ollama 1인스턴스 구조 자체(GPU 서빙 확장, 요청 큐잉)를 재검토해야 한다는 근거가 이번 측정으로 확보됐다.

**왜 먼저 해야 했나**: 이게 없었으면 #2~#9는 전부 코드 레벨 추측에 머물렀을 것. 실측으로 진짜 1순위 병목(GPU 자원)을 코드 문제(RAG always-on, WS 재연결 등)보다 먼저 걸러냈다.

---

## 2. Ollama 동시 요청 처리 한계 — 실측으로 확인됨

**근거**: [ChatWebSocketHandler.java:106-125](../backend/src/main/java/com/bigteam/btllm/chat/controller/ChatWebSocketHandler.java:106) —
모든 세션이 `chatClientFactory.get(provider, model)`로 얻은 캐시된 `ChatClient`를 쓰지만, 실제 추론은 결국 로컬 Ollama 프로세스 1개(GPU 슬롯 1개)가 순차 처리한다.

**측정 결과** (항목 #1과 동일 실험, VRAM 확보 후 3차 실행): 동시 접속이 1명일 때는 대부분 13~14초대에 끝났지만,
5명·10명으로 늘어난 구간에서는 33건 중 29건이 30초 안전 타임아웃에 걸려 아예 응답을 받지 못했다.
"의심"이 아니라 **동시 사용자가 2~3명만 되어도 대부분 30초 넘게 무응답 상태가 된다**는 것이 실측으로 확인됐다.

**문제**: 프론트는 그냥 `TypingIndicator`만 보여줘 사용자는 "느린 건지 멈춘 건지" 구분할 수 없다.
포트폴리오 데모에서 리뷰어 여러 명이 동시에 테스트하는 흔한 시나리오에서 바로 드러난다.

**개선안**:
- (a) 서버 쪽에 간단한 요청 큐를 두고 `WsResponse`에 `QUEUED` 타입 추가 — "다른 요청 처리 중입니다 (대기 N번째)" 최소한의 피드백
- (b) (선택, 인프라) `OLLAMA_NUM_PARALLEL` 조정 — 단, VRAM이 이미 빠듯해 실효성은 제한적
- (c) 포트폴리오 설명에 "로컬 단일 GPU 인스턴스 한계"를 정직하게 명시 — 실서비스라면 GPU 서빙 확장이 필요하다는 걸 스스로 아는 것 자체가 기술적 판단력으로 어필 가능

---

## 3. 유휴 후 모델 콜드스타트 지연

**근거**: [application.yaml](../backend/src/main/resources/application.yaml)에 `spring.ai.ollama.chat.keep-alive` 미설정.
Spring AI 공식 문서 확인 결과 기본값은 **5분** — 5분간 요청이 없으면 Ollama가 모델을 메모리에서 내린다.
이후 첫 메시지는 모델 재적재(수 초~수십 초, 모델 크기에 비례) 지연이 그대로 응답 시간에 얹힌다.
프론트는 이 경우도 그냥 [TypingIndicator](../frontend/src/components/TypingIndicator.tsx)만 표시한다 — 사용자는 원인을 알 수 없다.

**개선안**: `application.yaml`에 한 줄 추가. (실제 프로퍼티 경로는 `chat.options.keep-alive` — `chat.keep-alive`가 아니다.
spring-ai-autoconfigure-model-ollama-1.1.6.jar의 spring-configuration-metadata.json으로 직접 확인.)

```yaml
spring:
  ai:
    ollama:
      chat:
        options:
          keep-alive: -1s   # 무제한 유지 (Go duration 형식 — 단위 필수). 30m 등 운영 메모리 여유에 맞춰 조정
```

**적용 완료 — [application.yaml:35](../backend/src/main/resources/application.yaml:35)**

### 검증 (발견 → 진단 → 개선 → 검증)

| 단계 | 내용 |
|---|---|
| 발견 | Ollama 기본 keep-alive 5분 → 유휴 후 첫 메시지 응답이 느릴 것으로 추정 |
| 진단 | 모델을 명시적으로 언로드(`ollama stop qwen3:8b`) 후 콜드 호출과 즉시 재호출(웜) 비교 |
| 개선 | `chat.options.keep-alive: -1s` 설정 |
| 검증 | 콜드 호출 **37,197ms** → 웜 호출 **1,246ms** (약 **30배** 차이). `keep_alive:-1s`로 요청 시 `ollama ps` 결과 `UNTIL: Forever` 확인 — 설정이 실제로 반영됨 |

측정 방법: `ollama stop qwen3:8b`로 강제 언로드 → `/api/chat` 직접 호출(non-stream)로 왕복 시간 측정 → 동일 조건으로 즉시 재호출.
Spring 앱을 거치지 않고 Ollama 네이티브 API로 측정해 WAS·네트워크 오버헤드를 배제한 순수 모델 로딩 비용이다.

**주의**: `keep-alive: -1s`는 모델을 VRAM/RAM에 무기한 상주시킨다 — 여러 모델을 자주 전환하는 운영 환경이라면 메모리 압박으로 오히려
다른 모델 로딩이 느려질 수 있다. 이 프로젝트는 로컬 데모 단일 사용자 기준이라 상주 전략이 맞지만, 실제 배포 시엔 `30m`처럼
유한 값으로 절충하는 게 안전하다.

### 부록 — 이 설정 자체가 낸 회귀 버그 (트러블슈팅 소재)

최초 적용한 값은 `keep-alive: -1`(단위 없음)이었다. YAML 문서에는 정수 `-1`로 보이지만, Spring AI의 `OllamaOptions.keepAlive`
필드는 **String 타입**이라 이 값을 문자열 `"-1"`로 그대로 직렬화해 Ollama에 보낸다. Ollama는 Go의 `time.ParseDuration`으로
이 값을 해석하는데, 단위(`s`, `m`, `h`)가 없는 `"-1"`은 파싱 실패로 **모든 `/api/chat` 요청을 400으로 거부**했다 —
즉 성능 개선 설정 한 줄이 채팅 기능 전체를 마비시켰다.

| 단계 | 내용 |
|---|---|
| 발견 | 항목 #1의 k6 baseline 테스트가 100% 실패(`chat_stream_error_rate: 100%`)로 나옴 |
| 진단 | 로컬 프록시(`http.server` 기반)로 백엔드→Ollama 요청을 가로채 원문 확인 → 응답 본문 `{"error":"time: missing unit in duration \"-1\""}` 확보 |
| 개선 | `keep-alive: -1s`로 단위 명시 |
| 검증 | 동일 요청 재전송 → 200 OK, `ollama ps`에서 `UNTIL: Forever` 재확인, k6 baseline에서 에러율 0%로 복귀 |

**교훈**: Spring Boot의 relaxed binding이 YAML의 숫자 리터럴을 프로퍼티 타입(String)에 맞춰 조용히 문자열로 바꿔버리기 때문에,
설정값의 "형식"까지 실제 요청 로그로 검증하지 않으면 타입은 안 맞아도 기동은 되는(그리고 요청은 전부 실패하는) 상태를 놓치기 쉽다.

---

## 4. 모든 메시지에 무조건 RAG 검색 실행

**근거**: [ChatClientFactory.java:136-142](../backend/src/main/java/com/bigteam/btllm/config/ChatClientFactory.java:136) —
`SafeQuestionAnswerAdvisor`가 `defaultAdvisors`에 조건 없이 고정 등록되어 있다. "안녕", "고마워" 같은 잡담에도
pgVector 유사도 검색(임베딩 호출 1회 + DB 쿼리)이 매번 실행된다.

**문제**:
- 지연: RAG 정확도 실험에서 측정한 쿼리 지연 51~290ms가 관련 없는 대화에도 매 턴 추가된다.
- 품질: 지식베이스에 문서가 쌓일수록, 잡담에도 무관한 청크가 프롬프트에 섞여 들어가 답변 품질을 해칠 수 있다.

**개선안**: 이미 Tool Calling 인프라([LlmTools.java](../backend/src/main/java/com/bigteam/btllm/chat/tools/LlmTools.java))가 있으므로,
상시 advisor인 `QuestionAnswerAdvisor`를 빼고 `searchKnowledgeBase` 같은 **Tool**로 전환해 모델이 필요하다고 판단할 때만 호출하게 하는 안이 가장 자연스럽다.
(대안: 메시지 분류 후 조건부 advisor 적용 — 구현 복잡도는 더 높음)

**부가 가치**: 이 전환 자체가 "Advisor(상시 개입) vs Tool(선택적 호출)" 트레이드오프를 다루는 좋은 기술적 의사결정 문서 소재가 된다.

**적용 완료**
- [LlmTools.java](../backend/src/main/java/com/bigteam/btllm/chat/tools/LlmTools.java) — `searchKnowledgeBase` Tool 추가. 최초에는 기존값 topK=5를 유지했으나 실제 PDF 생성 품질 검증에서 하위 부록이 답변을 오염시켜 topK=3으로 조정했다(similarityThreshold=0.5 유지). 임베딩 실패 시 예외를 흡수하는 graceful degradation 적용
- [ChatClientFactory.java](../backend/src/main/java/com/bigteam/btllm/config/ChatClientFactory.java) — `defaultAdvisors`에서 RAG 어드바이저 제거, 4단계로 축소. 이제 안 쓰는 `SafeQuestionAnswerAdvisor.java`는 삭제
- [README.md](../README.md) — Advisor 체인·Tool 목록 갱신
- 검증: 컴파일 통과, 백엔드 재기동 후 정상 채팅 응답 확인 (E2E 1회, GPU 여유 상태)

### 트러블슈팅 — Tool 전환 후 RAG가 전혀 동작하지 않던 문제

Advisor→Tool 전환 직후 실제 사용 테스트에서 **RAG가 완전히 죽어 있었다.** PDF를 업로드하고
"방금 준 문서 무슨 문서야?"라고 물으면 "저는 아직 어떤 문서도 제공받지 않았어요"라고 답했다.

| 단계 | 내용 |
|---|---|
| 발견 | 사용자 테스트에서 인덱싱한 PDF 내용을 모델이 전혀 모름 |
| 진단 ① | `vector_store` 직접 조회 → PDF 21청크 정상 적재 확인. **ETL은 문제 없음** |
| 진단 ② | 백엔드 로그에 도구 호출 기록 0건. Ollama API로 직접 격리 테스트한 결과, 기존 description(`"…필요할 때만 사용하세요"`)으로는 `tool_calls: null`, 단정형 description + 시스템 프롬프트 힌트를 주니 정상 호출됨 → **원인은 description 어조** |
| 개선 | ① description을 억제형→단정형으로 변경 ② 시스템 프롬프트에 지식베이스 존재·호출 조건 명시 ③ 도구 호출 여부를 로그로 남김(원인 추적 불가 상태였음) |
| 검증 | 내용 질문("신청 자격이 어떻게 돼?") → PDF 근거로 상세 답변. 도구 호출 로그 정상 기록 |

**추가로 드러난 문제**: 도구는 호출되는데 **적중 0건**인 케이스가 있었다. "방금 준 문서 뭐야?" 같은
*메타 질문*은 문서 *내용*과 의미적으로 유사하지 않아 벡터 검색이 구조적으로 0건을 낸다
(similarityThreshold=0.5). 문서가 멀쩡히 인덱싱돼 있는데도 "없다"고 답하는 셈이다.
→ 검색 0건일 때 `EtlSourceService.listSources()`로 **인덱싱된 문서 목록을 대신 반환**하도록 보완.
검증 결과 "…'모두의 창업 프로젝트' 통합 모집 공고 (2차).pdf (21개 청크)가 있습니다"로 정확히 답한다.

**교훈**: 상시 Advisor를 Tool로 바꾸는 것은 "성능 최적화"처럼 보이지만, 실제로는 *동작 여부의 결정권을
모델에게 넘기는* 설계 변경이다. 작은 로컬 모델일수록 description 어조 하나에 호출률이 0%와 100%로 갈린다.
컴파일과 단순 E2E(잡담 1회)만으로는 이 회귀를 잡지 못했고, 실제 사용 시나리오(문서 업로드 후 질문)를
돌려봐야 드러났다.

---

## 5. WebSocket 끊기면 자동 복구 없음

**근거**: [useWebSocket.ts](../frontend/src/hooks/useWebSocket.ts) 전체 — `onclose` 핸들러(73행)는 콜백 호출만 하고 재연결 로직이 없다.

**문제**: 네트워크 순단, 백엔드 재배포, 프록시 유휴 타임아웃 등으로 연결이 끊기면 사용자는 새로고침 전까지 채팅을 못 한다.
헤더의 "연결 중..." 배지([ChatPage.tsx:472-476](../frontend/src/pages/ChatPage.tsx:472))만 뜬 채 방치된다.

**개선안**: 지수 백오프 재연결 (1s → 2s → 4s, 최대 N회) + 재연결 성공 시 안내.

**적용 완료**
- [useWebSocket.ts](../frontend/src/hooks/useWebSocket.ts) — `intentionalCloseRef`로 의도적 종료(언마운트·방 전환·모델 변경)와
  예기치 않은 종료를 구분. 후자만 1s→2s→4s→8s→16s 지수 백오프로 최대 5회 재연결 시도, `onReconnecting(attempt, max)` 콜백으로 진행 상황 전달
- [ChatPage.tsx](../frontend/src/pages/ChatPage.tsx) — 헤더 배지에 "재연결 중... (n/5)" 표시, 재연결 성공 시 원래 "연결됨"으로 복귀
- 검증: 타입체크(`tsc -b`) 통과

---

## 6. 스트리밍 에러 시 재전송 수단 없음

**근거**: [ChatPage.tsx:398-405](../frontend/src/pages/ChatPage.tsx:398) — `ERROR` 수신 시 에러 말풍선만 추가되고,
직전 사용자 메시지를 다시 보내는 버튼이 없다. 사용자가 직접 재입력해야 한다.

**개선안**: 에러 말풍선에 "다시 시도" 버튼 추가 — 직전 사용자 메시지 content를 그대로 재전송.

**적용 완료**
- [ChatPage.tsx](../frontend/src/pages/ChatPage.tsx) — `lastSentContentRef`로 직전 전송 내용 추적, `ERROR` 메시지에 `retryContent`로 첨부.
  에러 말풍선 아래 "다시 시도" 버튼 클릭 시 에러 제거 + 동일 내용 재전송(`handleRetry`)
- 검증: 타입체크 통과

---

## 7. WS 연결 전 전송 시 메시지 유실 가능

**근거**: [useWebSocket.ts:84-91](../frontend/src/hooks/useWebSocket.ts:84) `sendMessage` — `readyState`가 `OPEN`이 아니면 조용히 no-op,
실패 피드백이 없다. `handleSend`([ChatPage.tsx:442](../frontend/src/pages/ChatPage.tsx:442))가 `!isConnected`면 버튼을 막아주긴 하지만,
연결 상태가 막 바뀌는 찰나의 race는 이론상 여전히 가능하다.

**개선안**: `sendMessage`가 실패 시 `false`를 반환하도록 하고, 호출부에서 이미 있는 `pendingRef` 패턴([ChatPage.tsx:359](../frontend/src/pages/ChatPage.tsx:359))을 재사용해 큐잉.

**적용 완료**
- [useWebSocket.ts](../frontend/src/hooks/useWebSocket.ts) — `sendMessage`가 소켓이 `OPEN`이 아니면 `outboundQueueRef`에 담아두고 `onopen`에서
  순서대로 flush. 성공 시 `true`, 큐잉 시 `false` 반환
- [ChatPage.tsx](../frontend/src/pages/ChatPage.tsx) — 전송 버튼의 `!isConnected` 비활성 조건 제거 — 재연결 중에도 입력·전송 가능,
  연결 복구 즉시 자동 발송됨 (#5와 결합해 시너지)
- 검증: 타입체크 통과

---

## 8. 긴 대화 시 메시지 리스트 가상화 없음 (낮은 우선순위)

**근거**: [ChatPage.tsx:484-518](../frontend/src/pages/ChatPage.tsx:484) — `messages.map()`으로 전량 렌더링, assistant 메시지마다 `ReactMarkdown` 파싱.

**문제**: 대화가 수백 메시지로 길어지면 리렌더 비용이 누적된다. 스트리밍 중엔 매 토큰마다 마지막 메시지가 바뀌며 리스트 전체가 재조정된다.

**개선안**: `react-window`/`react-virtuoso` 가상 스크롤. **다만 포트폴리오/데모 규모에선 체감되기 어려워 지금 급하지 않음** — 백로그로 남겨도 된다.

### 시도했다가 되돌림 (의사결정 기록)

`react-virtuoso`로 실제 구현해봤으나 **되돌렸다.** 구현 자체는 타입체크를 통과했지만, 브라우저에서 검증하니
메시지가 1건 있는 상태에서 `virtuoso-item-list`의 자식이 0개 — 즉 대화 내용이 화면에 아예 안 나왔다.
`components` prop을 인라인 객체로 넘겨 매 렌더마다 리마운트되던 문제를 모듈 스코프로 빼서 고쳐봤지만 증상은 그대로였고,
Virtuoso가 마운트 시점의 컨테이너 높이를 `ResizeObserver`로 측정하는 구조라 검증 환경 문제인지 실제 버그인지
끝내 확정하지 못했다.

**판단**: 이 항목의 기대 이득은 "대화가 수백 건일 때의 리렌더 비용 절감"인데, 이 프로젝트의 실사용·데모 규모에서는
체감되지 않는다(위에서 이미 '낮음'으로 평가한 이유). 반면 지금 확인된 손실은 **핵심 기능인 메시지 표시가 깨지는 것**이다.
검증할 수 없는 최적화를 위해 확실한 회귀를 감수할 이유가 없어 원래의 `messages.map()` 렌더링으로 되돌리고
의존성(`react-virtuoso`)도 제거했다. 실제로 긴 대화에서 성능 저하가 관측되면 그때 다시 착수하는 것이 맞다.

---

## 9. 모바일 레이아웃 미대응 (용도에 따라 우선순위 조정)

**근거**: [ChatPage.tsx:133](../frontend/src/pages/ChatPage.tsx:133) 사이드바가 `w-64` 고정, 반응형 브레이크포인트가 없다.

**개선안**: `md:` 브레이크포인트로 사이드바를 햄버거 토글로 전환. 포트폴리오를 모바일로 시연할 계획이 있으면 올리고, 아니면 낮은 우선순위 유지.

**적용 완료**
- [ChatPage.tsx](../frontend/src/pages/ChatPage.tsx) — 사이드바를 `fixed` + `-translate-x-full` 기본 숨김으로 두고
  `md:static md:translate-x-0`으로 데스크톱에서는 기존과 동일한 고정 컬럼 유지. 모바일 전용 상단바(☰)로 열고,
  오버레이 클릭·닫기 버튼·방 선택 시 자동으로 닫힘
- 검증: 375x812 뷰포트에서 사이드바가 화면 밖(`left: -256px`)으로 숨고 ☰ 버튼이 노출됨을 확인,
  버튼 클릭 시 `translate-x-0`으로 전환되어 `left: 0`으로 들어오는 것까지 확인. 1280px에서는 기존 레이아웃 그대로 유지

---

## 실행 순서 제안

1. **k6 WS 시나리오로 baseline 측정** (#1) — 이후 모든 개선의 효과를 수치로 검증하기 위한 전제
2. **keep-alive 설정** (#3) — 5분 투자, 즉시 체감 개선, 트러블슈팅 소재로도 좋음
3. **동시성 실측 후 필요시 대기 안내** (#2)
4. **RAG always-on → Tool 전환** (#4) — RAG 정확도 실험과 묶어서 하나의 성능/정확도 개선 스토리로 문서화
5. **WS 재연결 + 재전송 + 전송 유실 방지** (#5, #6, #7) — 안정성 묶음, 한 번에 처리
6. #8, #9는 백로그
