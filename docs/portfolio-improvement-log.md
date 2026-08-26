# 포트폴리오 개선 작업 로그

이 문서는 BTLLM을 포트폴리오 수준으로 개선하면서 발견한 문제와 의사결정 과정을 기록한다.
각 항목은 `발견 → 진단 → 해결 → 검증 → 남은 한계` 순서로 작성한다.

---

## 2026-08-26 — 품질 게이트 복구

### 발견

이전 기능 작업 6개 커밋을 인수한 직후 기본 품질 게이트를 실행했다.

- 백엔드 `gradlew test`: 테스트 코드 컴파일 오류 2건
- 프론트엔드 `npm run lint`: React Hooks 규칙 오류 6건
- 프론트엔드 `npm run build`: 성공

기능 검증 위주로 작업하면서 Repository API 변경과 테스트 코드가 함께 갱신되지 않았고,
WebSocket 재연결 구현에서 React 렌더 단계에 ref를 직접 변경하고 있었다.

### 진단

#### 백엔드

대화 이력 순서를 안정화하려고 Repository 메서드를
`findByChatRoomIdOrderByCreatedAtAscIdAsc`로 변경했지만, 서비스 테스트 2곳은 삭제된
`findByChatRoomIdOrderByCreatedAtAsc`를 계속 호출했다. 운영 코드가 아니라 테스트 코드의
API 동기화 누락이어서 `compileTestJava` 단계에서 실패했다.

#### 프론트엔드

React 19 ESLint 규칙이 다음 패턴을 탐지했다.

- `useWebSocket`: 렌더 중 callback ref 4개 변경
- `ChatPage`: 렌더 중 `sendMessage` ref 변경 1건
- `ChatPage`: 마운트 effect에서 첫 메시지 state를 동기 변경 1건

콜백 ref는 WebSocket effect의 불필요한 재실행을 막는 데 필요하지만, 렌더 중 `.current`를
변경하면 React의 순수 렌더링 가정을 깨뜨린다. 첫 메시지는 props만으로 결정되므로 effect가
아니라 state/ref의 지연 초기값으로 표현할 수 있었다.

### 해결

- 테스트 mock을 실제 Repository 메서드명과 동기화
- callback ref와 `sendMessage` ref 갱신을 의존성이 명시된 `useEffect`로 이동
- 첫 사용자 메시지를 `useState` 지연 초기값으로 구성
- WebSocket 연결 후 보낼 첫 메시지는 `useRef(initialMessage)`로 초기화
- 기존 방의 저장 이력만 마운트 effect에서 비동기로 조회

WebSocket의 재연결 조건과 메시지 큐 동작은 변경하지 않고 React 생명주기 규칙만 바로잡았다.

### 검증

| 품질 게이트 | 수정 전 | 수정 후 |
|---|---:|---:|
| 백엔드 테스트 컴파일 | 오류 2건 | 오류 0건 |
| 백엔드 테스트 | 실행 불가 | 21건 통과, 실패 0건 |
| 프론트 lint | 오류 6건 | 오류 0건 |
| 프론트 프로덕션 빌드 | 성공 | 성공 |

백엔드 첫 실행은 샌드박스 네트워크 제한 때문에 Gradle 배포본 다운로드에 실패했다.
동일 명령을 네트워크가 허용된 환경에서 재실행해 코드 실패와 실행 환경 실패를 분리했다.

### 남은 한계

- 현재 검증은 단위 테스트·정적 분석·프로덕션 번들 생성까지다.
- WebSocket 재연결과 첫 메시지 자동 전송의 브라우저 E2E 회귀 테스트는 아직 자동화되지 않았다.
- 다음 기능 변경 전, 문서 기반 답변의 생성 품질을 별도 평가할 수 있는 기준이 필요하다.

