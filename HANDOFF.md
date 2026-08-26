# 인수인계 문서

최종 갱신일: 2026-08-27
작업 경로: `C:\Users\User\claude-project\personal-llm-remaster`
저장소: https://github.com/straycat405/personal-llm (브랜치 `main`)

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
| 6 | 동일 골든셋 provider 비교 실험 | **실패 원인을 검색 문제와 모델 체급 문제로 분리** |

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

1. **트랙별 사실 귀속 오류 — 현재 최우선**

   남은 실패 4건의 성격이 하나로 모인다: **여러 청크에 흩어진 사실을 트랙별로 올바르게
   연결하지 못한다.**

   | 문항 | 상태 |
   |---|---|
   | `prize-1` | 사실 2/2 포함했으나 금지 오답(일반/기술 상금을 1억으로 서술)에 걸림 |
   | `track-1` | 0/2 — 트랙별 지원 분야 구분 실패 |
   | `registration-1` | 1/3 — 사업자등록 시점 트랙 귀속 오류 |
   | `overview-1` | 3/4 — 선정 규모 수치 일부 누락 |

   비교할 후보(같은 골든셋으로 판정):
   - (a) `topK` 3 → 5 — 컨텍스트에 여유가 생겼으므로 이제 가능
     (청크 800 기준 topK=5도 약 4,478토큰이라 4096을 살짝 넘음 — topK 4부터 시도 권장)
   - (b) `num_ctx` 4096 → 8192 — KV 캐시 증가로 8GB VRAM 압박. 위험도 측정 필요
   - (c) 표 구조 보존 청킹 — 트랙별 표가 청크 경계에서 잘리는지 먼저 확인

2. **요약 청크 기능 재평가** — `DocumentSummarizer`는 동작하고 개요형 질의에서 1위로 검색되지만,
   현재 코퍼스에서는 본문 `1 사업 개요` 청크와 중복이라 점수를 올리지 못했다.
   기능은 유지 중이며, 청크 800 환경에서 켜고 끈 A/B를 아직 하지 않았다.

3. ~~provider 비교 반복 실행~~ — **완료.** 3회 반복 결과는 위 2절 참고.
   `PROVIDER_COMPARISON_REPETITIONS=3`으로 재현한다.

4. **의도 기반 thinking 라우팅 — 지연 개선의 다음 단계**

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

5. **`num_predict` 절단은 이미 시도했고 실패했다(반복 금지).** 512로 낮추면
   `[출처]`가 잘려 출처 표시율 100% → 37.5%로 붕괴하고 지연은 8.4초만 줄어든다.
   현재 기본값 2048은 폭주 방지용 안전장치이지 최적화 수단이 아니다.
2. **provider 비교 실험 반복 실행** — 현재 각 1회라 분산 미측정. 로컬 모델은 같은 8문항에서
   12.5%~25.0%로 흔들린 전력이 있어, 결론을 굳히려면 3회 반복이 필요하다.
3. **Qwen3 8B 생성 파라미터 A/B** — temperature 0.3 / 0.5 / 0.7 비교. 판정은 위 2행 문항으로.
4. **Qwen3.5 후보 비교** — `qwen3.5:4b-q8_0`(약 5.3GB) 우선. `qwen3.5:9b`는 6.6GB라
   bge-m3·KV cache까지 더하면 CPU offload 위험. **Ollama의 Qwen3.5 지원이 아직 불안정하다는
   보고가 있어** 저위험 후보부터 시도한다.
5. **백로그**: `[출처]` 조건부 표시 코드 가드레일, cross-encoder 리랭커(CPU 서빙),
   Google provider 키 부재 시 기동 실패 근본 수정.

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
- 나머지 하드웨어 주의사항(VRAM, `keep-alive: -1s`)은 아래 기존 문서 절을 그대로 따른다.

### 5. 실행 명령

```powershell
cd C:\Users\User\claude-project\personal-llm-remaster\backend
.\gradlew.bat test                              # 일반 테스트 (실험 제외)
.\gradlew.bat ragGenerationQualityExperiment    # PDF 답변 품질
.\gradlew.bat localConversationQualityExperiment
.\gradlew.bat providerComparisonExperiment      # 로컬 vs 상용 (과금 주의)
```

대상 변경 예시:

```powershell
$env:PROVIDER_COMPARISON_TARGETS = "ollama=qwen3:8b,openai=gpt-4o"
.\gradlew.bat providerComparisonExperiment
```

### 6. 보고서 위치

| 파일 | 내용 |
|---|---|
| `docs/portfolio-improvement-log.md` | **의사결정 기록의 본체.** 매 작업마다 누적 |
| `docs/provider-comparison-experiment.md` | 로컬 vs 상용 비교 (신규) |
| `docs/rag-generation-quality-experiment.md` | PDF 답변 품질 |
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
